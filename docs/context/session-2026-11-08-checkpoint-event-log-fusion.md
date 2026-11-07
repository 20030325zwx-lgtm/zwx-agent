# 会话摘要：Checkpoint 融合治理——事件日志（真相）+ 追加式快照（缓存）（2026-11-08 凌晨）

## 需求与决策

用户决定 checkpoint 采用「两种方式都要有」的融合治理：deepseek-harness 的事件溯源 +
LangGraph 式追加快照。方案详见 `docs/design/2026-11-08-checkpoint-event-log-fusion.md`。
原则：**日志是唯一真相，快照是"日志到 seq N 为止的重放缓存"**（带 last_event_seq 与
content_hash，可被日志重放验证/重建）。关联：本轮接了 2026-11-07 agent-loop-upgrade 会话
的 P0（worker 思考流式化，已随前一批提交）。

## 改动（后端）

- V49 迁移：`agent_run_event`（append-only，uq(run_id, seq)，FK CASCADE 到 agent_run）。
- `RunEventLog`（execution/store）：append（事务内 `SELECT..FOR UPDATE` **queryForList**
  锁 run 行 → MAX+1 分配 seq，双实例安全）、lastSeq、tail（降序读→升序返回）、
  `repairUnclosedToolCalls`（扫描尾部未闭合 tool/call → 合成 tool/repair 事件）。
- `RunEventLogHook`（@Component，order=200）：run/start|end、step/start|end、tool/call|result、
  assistant/attempt；callId 配对用 runId+workerKey 内存队列（ToolCallWorkerContext 区分 worker）。
- `AgentHook` 新切点 `onThinkRetry` + 管线 fire + `ToolCallAgent.retryThink` 触发
  （签名加 initialError 参数）。
- `GraphCheckpointJson`：GraphCheckpoint ↔ state_json（schema_version=1，读端容忍缺字段）。
- `AgentRunCheckpointStore` 增 appendGraphCheckpoint / latestGraphCheckpoint（sha256 校验，
  不符→empty 回退）/ pruneToLatest（runId 维度）。
- `ManusGraphOrchestrator`：构造器加 checkpointStore + runEventLog 两参；persistCheckpoint
  双写（v2 append → 行内列）；开关 `app.agent.checkpoint.v2`（默认开）；execute() 的 resume
  分支先修尾（repair 事件落日志 + activity 推用户）。
- `ManusAgentController.resumeManusRun`：恢复读 v2 latest 优先，回退行内列（老 run 零迁移）。
- `AgentRunService.finish`：COMPLETED 时 pruneToLatest（INTERRUPTED 保留全部诊断）。

## 验证

- 单测 10/10：GraphCheckpointJsonTest 3 + RunEventLogTest 4 + 既有 StoreTest 3。
- 全量回归 146 测试类：仅 3 个已知基线失败（ToolGuardHookTest / MediaJobSchedulerTest /
  PgVectorVectorStoreConfigTest，DashScope 欠费）。
- 真实验证（本地，admin）：任务跑通 COMPLETED，DB 实测——
  `agent_run_event` 4 条（run/start→step/start→step/end→run/end，seq 递增），
  `agent_run_checkpoint` v2 快照 1 条且 state_json 结构正确（plan 字段完整）。

## 过程中的 bug（已修）

1. **`jdbc.update()` 执行 `SELECT..FOR UPDATE` 抛"传回预期之外的结果"**——JdbcTemplate.update
   只收 DML，行锁改走 `queryForList`。教训：Spring update 对 SELECT 一律报错，锁行查询必须走 query 系。
2. 测试 captor 把"锁行+插入"两次 update 混在 `times(2)` 里——改为按新语义分开断言。

## 遗留（P3 路线）

- 消息历史改日志投影（deriveMessages）、fork 分叉、worker messageList 入快照（恢复免重跑整节点）。
- 事件日志审计查询 UI（现可 SQL 直查）。
- hash 自洽的 DB 侧校验用单测覆盖（PG 未装 pgcrypto，DB 侧 sha256 无法直接比）。

## 注意

- 开关：`app.agent.checkpoint.v2`（默认开，关=只写行内列旧行为）。
- 事件日志无 UI，排查用 SQL：`SELECT * FROM agent_run_event WHERE run_id='...' ORDER BY seq;`
- 本轮含 git push + 线上部署（用户明确要求）；部署走 `deploy/scripts/deploy-to-server.sh`
  （本地私有脚本，含 SSH 凭据，绝不提交/粘贴）。
- 上一批（agent-loop-upgrade P0）的提交在 2026-11-07 会话完成；本批为融合治理 + P0 收尾后的一切。
