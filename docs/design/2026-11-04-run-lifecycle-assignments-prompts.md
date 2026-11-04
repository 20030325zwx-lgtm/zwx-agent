# 运行阶段语义化 · 派工与后台配额 · 提示词版本化（Step 1-3 方案与实现）

日期：2026-11-04
来源：`docs/design/2026-11-04-deepseek-superagent-design-review.md` 的 Step 1/2/3。
目标：让"任务在等谁做什么"变成结构化事实，给长任务留出后台槽位，并让提示词可追溯。

## 1. 运行阶段（Step 1，迁移 V27）

`agent_run` 新增：

| 列 | 含义 |
|---|---|
| `phase` | 规范阶段：`QUEUED / WORKING / WAITING_HUMAN / BLOCKED / FAILED / DONE / STOPPED` |
| `waiting_reason` | `CONFIRM_PLAN / CONFIRM_TOOL / NEED_INPUT` |
| `waiting_detail` | 具体问题/说明（结构化提问正文） |
| `background_jobs` | 本轮结束后仍在跑的媒体任务数 |
| `background_deadline` | 后台槽位保留截止（默认 +30 分钟） |
| `prompt_versions` | 本次 run 的提示词版本快照 JSON |

推导规则（`RunPhase.derive`，纯函数 + 单测）：

```
COMPLETED + background=0      → DONE
COMPLETED + background>0      → WORKING（保留槽位）
INTERRUPTED + waiting_reason  → WAITING_HUMAN
INTERRUPTED（无等待原因）      → STOPPED
FAILED                        → FAILED
RUNNING                       → WORKING
其它/null                      → QUEUED
```

写入点全部收敛在 `AgentRunService`：`start/finish/restart/markPlanPending/markPendingConfirmation/
markInterrupted/markWaitingInput/markInputAnswered`。旧 `status` 保留，前端展示与分组用 `phase`。

### 结构化提问（askHuman）

- 新工具 `askHuman(question, options?)`（`READ_ONLY`，不弹确认）：把问题写进 `waiting_reason=NEED_INPUT` + `waiting_detail`，
  并提示模型"本轮结束，别猜"。
- `POST /ai/manus/conversations/{cid}/runs/{rid}/answer {answer}`：校验 run 处于等待输入 → 清空等待（老 run 收口为 DONE），
  答复正文由前端作为新消息发送，新一轮带上上下文继续。
- 前端：对话区顶部出现可回答的横幅（问题 + 输入框 + 提交）；运行检查器显示"阶段/等待/后台任务/派工/提示词版本"。

## 2. 派工与后台配额（Step 2，迁移 V28）

`agent_run_assignment`：`(run_id, attempt)` 唯一，字段 `executor_type / session_ref / work_dir / status / result_summary / started_at / ended_at`。

- `start()` 创建 attempt=1；`restart()`（续跑）创建 attempt=resume_count+1；`finish/interrupt/markPlanPending/markInputAnswered` 结束当前 RUNNING 派工并写摘要。
- 视图 `AgentRunView.assignments` 暴露派工历史（面板显示"派工 N 次"）。
- 后台配额：`finish()` 统计 `creative_media_job.source_run_id = runId AND status NOT IN (终态)`；>0 时 phase 保持 WORKING，
  `background_deadline = now + 30min`；`RunPhaseSweeper` 每 30 秒扫尾：任务清零 → DONE，超时 → BLOCKED（等待人工）。

## 3. 提示词版本化（Step 3，迁移 V29）

`prompt_version(prompt_key, version, content, base_version, origin, note)` + `prompt_active(prompt_key, version)`。

- `PromptVersionService.active(key, fallback)`：无覆盖时返回 version 0 + 内置默认文本；`save()` 用 `base_version` 做乐观并发：
  与当前版本不一致直接拒绝（提示刷新），成功后写不可变新版本并切换 active。
- 规划/质检/汇总节点通过 `PromptSource` 取当前生效内容（`prompt_source.resolve(key, 内置默认)`），无服务时（单测/旧路径）回退默认。
- `agent_run.prompt_versions` 在 run 开始时快照（`recordPromptSnapshot`），事后可答"这次 run 用了哪版提示词"。
- API：`GET /ai/prompts`（当前版本与内容）、`GET /ai/prompts/{key}/versions`（历史）、`POST /ai/prompts/{key}`（`{content, baseVersion, note}`）。
  提示词编辑 UI 暂缓，先用 API/后续设置页接入。

## 4. 兼容与风险

- 旧数据：V27 按 `status + verification_status + pending_confirmation` 回填 phase/waiting_reason，前端旧逻辑（status）不受影响。
- `finish()` 现在会查媒体任务表；表不存在（裁剪部署）时按 0 处理，不阻塞收口。
- 提问答复走"新消息"而不是"同一 run 内恢复"：老 run 收口为 DONE，上下文由会话历史携带；后续若要同 run 恢复，需要把答复写入
  GraphState.INPUT 并从 checkpoint 续跑。
- 提示词权限：当前 Controller 只校验登录态，没有租户级隔离（提示词是全局配置）；多租户时需要加作用域。

## 5. 验证

- `RunPhaseTest` 6/6（阶段推导与等待原因解析）；`AgentRunViewTest` 2/2；既有工具/导出测试全通过。
- backend `mvn compile` / test-compile 通过；frontend `vite build` 通过。
- 未做本地端到端：需要重启后端让 Flyway 执行 V27-V29 并加载新工具（与并行会话时间冲突，留到下次重启/部署验证）。
