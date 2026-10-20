# 会话摘要：SSE 断线重连与工具调用幂等（2026-10-20）

## 改动内容

### P2：SSE 断线重连与事件回放

- 新增 `RunStream`：每个 run 维护单调递增事件序号、内存事件历史、当前 emitter、断开宽限期和停止回调。
- SSE 事件统一带 `id=<runId>:<sequence>`；回答 chunk、activity、rag-trace、run、interrupted、[DONE] 都可回放。
- 客户端断开时不再立即取消图执行；默认保留 30 秒宽限期，期间重连可继续接收后续事件。
- 新增 `GET /api/ai/manus/conversations/{conversationId}/runs/{runId}/reconnect?lastEventId=...`：
  - 同一实例内重放缺失事件并继续实时推送；
  - 事件历史缺口或 run 不在本实例时返回 409；
  - run 完成后保留 120 秒事件历史。
- 前端 SSE 解析器记录事件 `id`；断线后最多重试 3 次，带 `lastEventId` 重连；失败后回退到重新加载历史。
- 配置：`app.agent.sse-reconnect-grace-seconds`、`app.agent.sse-history-max-events`、`app.agent.sse-replay-retention-seconds`。

### P3：工具调用幂等账本

- 新增 V13 `agent_tool_call`：唯一键 `(run_id, tool_name, arguments_hash)`，状态 `PENDING/SUCCEEDED/FAILED/UNKNOWN`。
- `AgentToolCallLedgerService`：
  - 新调用插入 PENDING；
  - 成功复用结果；失败返回上次失败结果；
  - 恢复时发现 PENDING 标为 UNKNOWN，阻止盲目重放副作用；
  - 结果上限 200k 字符。
- `IdempotentToolCallback` 包装超级智能体本次 run 的全部工具；`ManusGraphOrchestrator` 注入账本并统一包装。
- travel/test/love 的单体链路暂未接入。

### 文档与 README

- 新增设计文档：
  - `design/2026-10-20-super-agent-run-resume.md`
  - `design/2026-10-20-sse-reconnect-replay.md`
  - `design/2026-10-20-tool-call-idempotency.md`
- 更新 `design/README.md` 索引。
- 更新 `README.md`：当前能力、超级智能体恢复接口、SSE 重连、工具幂等、迁移说明。

## 验证结果

- 定向测试通过：`RunStreamTest`、`IdempotentToolCallbackTest`、`ResumeRoutingTest`、`ManusGraphOrchestratorTest`、`ManusGraphOrchestratorInterruptionTest`、`VerifierRoutingTest`、`ConversationLockManagerTest`、`AgentHookPipelineTest`、`ExecutionTraceHookTest`。
- `mvn -o -DskipTests package` 成功；`npm run build` 成功；`git diff --check` 无错误。
- Flyway V13 已应用；后端重启后 `/api/health` 返回 `ok`。
- 真实 SSE 重连冒烟：
  - 超级智能体执行中主动断开 curl；
  - 使用最后一个 `Last-Event-ID` 调用 reconnect；
  - 成功补发后续 chunk 和 `[DONE]`，run 最终 `COMPLETED`。
- 带联网工具的 run 在 V13 账本启用后正常完成，说明包装层未破坏工具调用链路。

## 遗留事项

- SSE 事件历史在 JVM 内存中；进程重启后不能实时重连，需使用 `resume`。
- 多实例部署时 reconnect 必须命中执行实例；跨实例需要共享事件总线或粘性路由。
- 页面关闭/主动取消后仍会继续执行最长 30 秒；如需立即停止应增加显式取消接口。
- 幂等键是 `runId + toolName + arguments`；同一 run 内相同参数的重复调用会被视为同一次，需要后续增加调用意图键。
- FAILED 调用缓存失败结果，不自动重试；瞬时故障需用户重新发起。
- 工具账本保存参数和结果，仍需纳入数据保留、脱敏和删除策略。
- 全量 `mvn test` 仍受既有 Spring 上下文扫描挂起问题影响。
- 本轮改动未提交；此前一轮已提交为 `b616f70`。
- 冒烟测试继续产生测试用户、会话和 run 记录，未做宽泛清理。

## 环境注意事项

- `src/main/resources/application-local.yml` 未读取、编辑、复制或加入 Git。
- 后端由 launchctl 管理；已重新打包并重启。
- 本地 PostgreSQL 已应用 V11、V12、V13。
