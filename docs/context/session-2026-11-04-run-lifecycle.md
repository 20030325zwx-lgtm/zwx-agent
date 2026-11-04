# 会话摘要：run 阶段语义化 + 派工/后台配额 + 提示词版本化（2026-11-04）

## 改动内容

按 `docs/design/2026-11-04-deepseek-superagent-design-review.md` 的 Step 1-3 实施（方案细节见
`docs/design/2026-11-04-run-lifecycle-assignments-prompts.md`）：

1. **V27**：`agent_run` 增 `phase / waiting_reason / waiting_detail / background_jobs / background_deadline / prompt_versions`，按旧字段回填。
2. **V28**：`agent_run_assignment`（run 的一次执行绑定，attempt 唯一，可重派/换执行器）。
3. **V29**：`prompt_version` + `prompt_active`（不可变版本 + 活跃指针）。
4. 新增 `RunPhase` / `WaitingReason` / `PromptSource`；`AgentRunService` 增加阶段写入、派工创建/结束、后台扫尾、
   `markWaitingInput/markInputAnswered`、媒体任务计数；`RunPhaseSweeper` 每 30s 扫后台任务。
5. 新工具 `askHuman`（READ_ONLY）+ `POST /runs/{runId}/answer`；`AgentRunView` 暴露 phase/assignments/promptVersions。
6. 三个节点（planner/verifier/aggregator）支持 `PromptSource`；run 开始时写入提示词版本快照；`/ai/prompts` 查询/保存/历史。
7. 前端：等待补充信息横幅（问题+输入+提交）、运行检查器显示阶段/等待/后台/派工/提示词版本、api 增加 answer/prompts。

## 验证结果

- 后端 compile / test-compile 通过；`RunPhaseTest` 6/6、`AgentRunViewTest` 2/2，既有工具/导出测试全通过。
- 前端 `vite build` 通过。
- 未做端到端：需要重启后端执行 Flyway V27-V29（本地与并行会话冲突，未重启）。

## 遗留事项

- 提示词编辑设置页（当前只有 API）；多租户提示词作用域。
- askHuman 的答复走"新消息/新 run"；如需同 run 内恢复，要把答复注入 GraphState.INPUT 并从 checkpoint 续跑。
- 后台配额目前只统计媒体任务（creative_media_job）；其它长任务（长命令）接入需要统一的后台任务表。
- 派工目前只有 local 执行器；desktop/remote 执行器落地时复用该表。

## 环境注意事项

- Flyway 迁移号已到 V29（并行会话用到 V26，注意后续编号）。
- `finish()` 会查 `creative_media_job`；裁剪部署缺表时按 0 处理。
