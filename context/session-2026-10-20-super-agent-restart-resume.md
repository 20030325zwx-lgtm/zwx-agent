# 会话摘要：超级智能体跨重启续跑（2026-10-20）

## 改动内容

### P0：先保证用户轮次不丢

- 超级智能体改为请求开始即 `startUserTurn` 落 USER 消息，执行完成后再更新状态；崩溃时至少保留用户提问。
- 新轮次开始前把同会话遗留的 `IN_PROGRESS` USER 消息标记为 `INTERRUPTED`，避免永久占用“正在处理”。
- 流式回答按 chunk 缓冲；客户端断开、超时或异常时保存已生成的部分回答、活动记录和 `INTERRUPTED` 状态，不再只写占位语。
- `AgentConversationMessage` 增加 `status` 字段，Super Agent 历史消息恢复后可显示“回答被中断，已保留部分内容”和“继续生成”入口。
- Super Agent 请求增加 `clientRequestId`，前端发送时生成幂等 ID。

### P1：agent_run 检查点与恢复

- 新增 V11 `agent_run`：记录 run 状态、输入/历史/知识上下文、最后完成节点、下一节点、plan/task_results/activities/events、验收状态、最终/部分回答、assistant message 关联。
- 新增 V12 `agent_instance` 心跳与 `owner_instance`：
  - 每个 JVM 注册实例并每 5 秒心跳；
  - 租约记录 owner_instance，崩溃实例心跳超过 15 秒后租约可被接管；
  - 启动及每 15 秒回收心跳失效实例留下的 RUNNING run。
- `AgentRunService` 按节点持久化检查点；`AgentRunRecoveryJob` 把孤儿 run 标为 INTERRUPTED 并补齐 assistant 消息。
- 新增 `GET /api/ai/manus/conversations/{conversationId}/runs/{runId}/resume` SSE 恢复接口；`GET .../runs/{runId}` 只读查看 run 状态。
- 恢复路由：planner 检查点 → workers；workers → verifier；verifier 按 pass/revise/unverified 路由；aggregator 完成但未收尾 → 重跑 aggregator。
- 恢复时清空上一轮 worker 结果（resumeFrom=workers），避免 AppendStrategy 重复拼接；返工恢复同样清理旧结果。
- 每个 run 编译独立图实例，修复图库共享 `OverAllState` 导致的跨 run 状态泄漏（旧 events/plan/final answer 串轮）。
- 图库 `NodeOutput` 使用共享状态引用且输出标签与实际完成节点存在错位，检查点改为从状态内容推断最后完成节点（finalAnswer → aggregator，verification → verifier，taskResults → workers，plan → planner）。

### 前端

- `SuperAgent.vue` 映射消息 `status` 和 `executionRunId`；接入“继续生成”按钮，调用 resume SSE。
- `api/index.js` 新增 `resumeManusRun`。

## 验证结果

- 定向测试通过：`ManusGraphOrchestratorTest`、`ManusGraphOrchestratorInterruptionTest`、`ResumeRoutingTest`、`VerifierRoutingTest`、`PlannerNodeKnowledgeRoutingTest`、`WorkersNodeKnowledgePromptTest`、`AggregatorNodeUserAnswerTest`、`ConversationLockManagerTest`、`AgentHookPipelineTest`、`ExecutionTraceHookTest`。
- `mvn -o -DskipTests package` 成功；`npm run build` 成功；`git diff --check` 无错误。
- Flyway V11、V12 已应用到本地 PostgreSQL；launchctl 重启后 `/api/health` 返回 `ok`。
- 真实崩溃恢复冒烟：
  - 在 planner 完成后 SIGKILL 后端，重启后 run 状态为 INTERRUPTED，`lastNode=planner`、`nextNode=workers`；
  - 调用 resume 后从 workers 继续（日志中未重新执行 planner），最终 USER/ASSISTANT 均为 COMPLETED，回答为真实联网新闻摘要；
  - resume 进行 2 秒时 run 仍为 planner/workers 阶段，没有因旧 verification 字段误跳到 verifier。
- 另验证了无检查点中断（重启后从 planner 开始）和普通多轮请求的检查点落库。

## 遗留事项

- 未实现 SSE `Last-Event-ID` 回放；断线后前端通过重新加载历史恢复，不保证逐事件续传。
- 工具副作用幂等尚未实现：恢复 workers 会重跑该节点，文件写入、下载、外部 SQL、MCP 调用可能重复；需要 callId/幂等键和“结果未知”处理。
- 恢复粒度为节点级；workers 内部已完成的子任务不会被跳过，整节点重跑。
- `agent_run` 目前只覆盖超级智能体；travel/test/love 仍使用原有轮次持久化。
- 全量 `mvn test` 仍存在既有的 Spring 上下文扫描挂起问题，本轮未触碰。
- 本轮未提交代码；用户原有未跟踪文件 `design/2026-09-28-deepseek-harness-vs-yu-agent.md` 保留。
- 崩溃/恢复冒烟创建了多个测试用户、会话和 run 记录，未做清理。

## 环境注意事项

- `src/main/resources/application-local.yml` 未读取、编辑、复制或加入 Git。
- 后端由 launchctl 管理；重启前已重新打包 JAR。
- 本地 PostgreSQL 已应用 V11/V12；`agent_instance` 心跳行由当前实例维护。
