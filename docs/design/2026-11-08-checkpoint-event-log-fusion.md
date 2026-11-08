# Checkpoint 融合治理：事件日志（真相层）+ 追加式快照（缓存层）（2026-11-08）

## 背景与决策

用户确认 checkpoint 采用「两种方式都要有」的融合治理。对标结论（2026-11-08 调研）：

- **deepseek-harness（开源，本地有源码）**：无快照概念，纯事件溯源——append-only JSONL 事件日志是唯一真相，
  消息历史是日志投影（deriveMessages），崩溃恢复=repair.ts 修尾（未闭合工具调用合成"结果未知"的
  **模型可见**合成结果，措辞教模型"只读/幂等可重试、有副作用先核实"），fork=复制日志前缀+合成关闭事件。
- **LangGraph**：快照派——每 superstep 序列化 channel values，PostgresSaver 落 checkpoints + pending_writes，
  thread_id + checkpoint_id，支持 time travel。
- **框架（spring-ai-alibaba-graph 1.0.0.2）**：只有 Memory/Redis/Mongo/FileSystem 四种 saver，无 JDBC 实现；
  且 OverAllState 含不可序列化运行时对象（ToolCallback/RunContext），持久化 saver 不可用 → 自建正确。

## 融合原则（一句话）

> **日志是唯一真相，快照是"日志到 seq N 为止的重放缓存"**——快照带 last_event_seq 与 content_hash，
> 可被日志重放验证/重建/丢弃，二者永不冲突。

## 治理规则

| 层 | 组件 | 职责 | 失败语义 |
|---|---|---|---|
| 真相层 | `agent_run_event`（V49，append-only，seq 锁 run 行分配） | 事件记录、审计、修尾、fork 基础 | 写失败仅 warn，绝不阻断主流程 |
| 缓存层 | `agent_run_checkpoint`（追加式，checkpoint_no MAX+1） | 快速恢复；state_json=GraphCheckpoint 投影 + last_event_seq | 写失败降级为行内列；恢复时 hash 校验不过→回退 |
| 展示层 | `agent_run` 行内 JSONB 列（原有） | 列表/详情页直读 | 纯缓存，可随时由 v2 快照重建 |

- **写路径**：先落日志（真相）→ 后打 v2 快照（缓存）→ 再刷行内列（展示）。
- **读路径**：恢复=v2 快照优先（hash 校验）→ 行内列回退；审计/修尾/fork 走日志。
- **清理由**：run COMPLETED 收口时 v2 快照 pruneToLatest（INTERRUPTED 保留全部，恢复与诊断仍需要）。
- **不变式**（借 dsh"model-visible means logged"）：凡进模型上下文的事件必须先在日志里；
  快照只打在闭合边界，永不存"跑了一半"的状态。

## 介质决策：为什么是数据库表而不是 dsh 的 JSONL 文件

dsh 用 JSONL 是因为它是**单机 CLI**：单进程、单写者、session 就在本机磁盘，文件是零依赖的正确选择。

Aster 的两种部署形态**都已有 PostgreSQL**：

| 形态 | 持久化设施 | 事件日志落点 |
|---|---|---|
| 服务器端（多实例 + 共享库） | 共享 PostgreSQL | `agent_run_event` 表（行锁分配 seq，双实例安全） |
| 桌面端（Electron，`desktop/`） | **捆绑的本地 PostgreSQL**（prepare-pgserver：initdb→建库→pg_ctl，main.cjs 全生命周期管理） | 同一张表，落在本机 PG（数据目录即本地文件，单写者语义等价） |

- 一套 `RunEventLog`（JdbcTemplate 实现）覆盖两种形态；若按形态分介质（DB + JSONL），修尾/投影/审计逻辑要写两遍，且跨介质无法保证快照 `last_event_seq` 与日志的事务一致性——违背"单一真相"原则。
- JSONL 实现是**接口已抽象好的备用项**：若未来桌面端去 PG 化，新增 `RunEventLogJsonl` 实现类即可，hook/修尾/恢复代码零改动。
- 排查体验：`SELECT * FROM agent_run_event WHERE run_id='...' ORDER BY seq;` 等价于 `tail -f`；需要文件形态时一条查询即可导出 JSONL。
- **教训**：介质选择跟随部署形态里已有的持久化设施，而不是照抄参考项目的介质——参考的是语义（append-only、seq、成对闭合、投影重建），不是文件。

## P1 落地（快照派做对）

- `GraphCheckpointJson`：GraphCheckpoint ↔ state_json 编解码（schema_version=1，读端容忍缺字段）。
- `AgentRunCheckpointStore` 增：`appendGraphCheckpoint`（权威打点）、`latestGraphCheckpoint`
  （hash 校验，不符→empty 回退）、`pruneToLatest`（终态收口）。
- `ManusGraphOrchestrator.persistCheckpoint` 双写：v2 append（AFTER_STEP）+ 行内列；开关
  `app.agent.checkpoint.v2`（默认开）。last_event_seq 取 RunEventLog 当前尾 seq。
- `ManusAgentController.resumeManusRun`：恢复读 v2 latest 优先，回退行内列——老 run 零迁移。
- `AgentRunService.finish`：COMPLETED 时 prune。

## P2 落地（日志派地基）

- V49 迁移 `agent_run_event`（FK ON DELETE CASCADE，uq(run_id, seq)）。
- `RunEventLog`：append（事务内 FOR UPDATE 锁 run 行 → MAX+1 分配 seq，双实例安全）、
  lastSeq、tail（降序读→升序返回）、`repairUnclosedToolCalls`（扫描尾部未闭合 tool/call
  → 合成 tool/repair 事件，摘要措辞面向用户/模型）。
- `RunEventLogHook`（order=200，自动注册）：run/start|end、step/start|end、tool/call|result、
  assistant/attempt。callId 配对：beforeToolCalls 按 runId+workerKey 入内存队列，afterToolCalls
  同序消费（Spring AI 响应顺序=计划顺序）；崩溃后的配对靠日志扫描。
- `AgentHook` 新切点 `onThinkRetry` + 管线 fireOnThinkRetry + `ToolCallAgent.retryThink` 触发。
- 恢复接入：`execute()` resumeCheckpoint 分支先修尾（repair 事件落日志 + activity 推给用户）。

## 事件类型清单（首版）

`run/start|end`（end 带 reason: FINISHED/INTERRUPTED/ERROR）、`step/start|end`、
`tool/call|result|repair`（call 带 callId/tool/workerKey/argumentChars）、`assistant/attempt`（模型重试）。

## 明确不做（后续路线）

- P3：消息历史改日志投影（deriveMessages，日志成唯一来源）、fork 分叉、
  worker 内部 messageList 入快照（恢复免重跑整节点）。
- 事件日志的审计查询 UI（当前可 SQL 直查）。

## 验证

- 单测 10/10：GraphCheckpointJsonTest 3（round-trip/容忍缺字段/摘要）+ RunEventLogTest 4
  （锁行+seq 分配/身份跳过+吞异常/修尾合成/全闭合空）+ 既有 StoreTest 3。
- 全量回归与真实验证见会话摘要 `docs/context/session-2026-11-08-checkpoint-event-log-fusion.md`。
