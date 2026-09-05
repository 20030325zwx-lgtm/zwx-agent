# 方案 04：并发模型与执行正确性

## 1. 目标

并发是常态而非异常：多人同时使用不互相卡死，同一会话并发操作不串数据，后台任务队列可预期。完成后：

- 智能体执行使用专用线程池，容量可控、耗尽时行为可预期（排队/拒绝，而非全站僵死）。
- 同一会话串行化；一轮消息的 USER/ASSISTANT/引用/trace 原子落库。
- 执行事件序号生成原子化；索引任务队列可承载真实并发上传。

## 2. 现状问题（审计证据）

| 问题 | 位置 |
| --- | --- |
| manus 主循环跑 `ForkJoinPool.commonPool`，每步再 `supplyAsync` + 阻塞 `get`：每个运行中 agent 占 2 线程（1 个长期阻塞），3-4 个并发用户即耗尽 commonPool，全部请求互卡到 5 分钟超时 | `agent/BaseAgent.java:124、268-271` |
| RAG 检索 `CompletableFuture.supplyAsync` 也用 commonPool，超时 `cancel(true)` 无法中断 JDBC，线程继续被占 | `rag/LoveRagService.java:37-42` |
| 同一会话并发发消息无互斥：历史 restore 后并行执行，落库交错 | `AiController.java:434-453` |
| `saveCompletedTurn` 并发插入 USER/ASSISTANT 交错；引用/trace 靠"最新一条消息"子查询定位，串轮 | `conversation/LoveConversationService.java:79-113` |
| manus 用的 `saveCompletedTurn` 重载无 `@Transactional`，半轮记录 | `conversation/AgentConversationService.java:112-117` |
| 执行事件 `MAX(sequence)+1` 读后写 + `UNIQUE(run_id, sequence)`，与 SSE `onNext` 并发时撞约束抛异常打断整条流 | `execution/AgentExecutionTraceService.java:22-30`、`V1__create_application_tables.sql:97` |
| 索引线程池 core=max=1、queue=4、AbortPolicy：第 5 个并发上传 500，文档永停 PENDING | `rag/LoveKnowledgeIndexConfig.java:13-17`、`AiController.java:217-218` |

## 3. 方案设计

### 阶段 1：线程模型

1. 新增 `AgentExecutorConfig`：专用 `ThreadPoolTaskExecutor`
   - `agentExecutor`：核心数 = 配置 `app.agent.max-concurrent-runs`（默认 CPU-2），队列有界（如 20），拒绝策略 `CallerRuns` 或显式 429。
   - `ragExecutor`：核心 4-8，供检索使用，与 agent 执行隔离。
2. `BaseAgent.runStream` 增加 executor 参数（Controller 注入）；主循环与 `step()` 改为**同线程顺序执行**（每步本来就是串行依赖，`supplyAsync + get` 没有意义），只在需要超时控制的场合用 `ScheduledExecutorService` 做看门狗。
3. 超时语义保留：总时长 `MAX_RUN_MILLIS` 看门狗触发 `requestStop()`（与方案 03 的停止机制复用），而非依赖线程中断。

### 阶段 2：会话级互斥

1. `ConversationLockManager`：`ConcurrentHashMap<String, ReentrantLock>`（或 Caffeine `cache.asMap().computeIfAbsent`），key = `conversationId`，带空闲自动清理。
2. 三个 chat 入口（love/travel/manus）在开始生成前 `tryLock`：拿不到锁返回 409 + 明确错误事件（前端提示"上一条回复还在生成中"）。
3. 锁在 `doFinally` 中释放（配合方案 03 的兜底路径，保证任何中断都释放）。

### 阶段 3：落库原子性

1. `saveCompletedTurn` 改为单事务整轮写入：一次事务内 INSERT USER + INSERT ASSISTANT + UPDATE/INSERT 引用与 trace（引用不再用"最新一条"子查询，改为方法参数显式传入 messageId）。
   - 依赖方案 03 的"USER 先行落库"：此时 saveCompletedTurn 退化为"创建 ASSISTANT 消息 + 关联元数据"，天然无交错。
2. `AgentConversationService.saveCompletedTurn` 补 `@Transactional`。
3. 执行事件序号：
   - 首选：`agent_execution_event` 的 sequence 改为每 run 一个 DB 序列（`CREATE SEQUENCE`，Flyway 迁移）或 `INSERT ... SELECT COALESCE(MAX(sequence),0)+1 ... FOR UPDATE` 事务化。
   - 兜底：捕获唯一约束冲突重试 3 次（指数退避），并确保异常不再外溢到 SSE 流（`record()` 内部吞掉并记日志）。

### 阶段 4：后台任务队列

1. 索引线程池：core=2、max=4、queue=100、拒绝策略改为提交时返回"排队中"（Controller 捕获 `TaskRejectedException` 返回 429 + 提示稍后重试），文档状态已有 PENDING/INDEXING/READY/FAILED 可承载。
2. 提交索引任务改为"先落库任务记录再异步执行"（`@Async` 提交失败时任务仍在 PENDING，由定时补偿扫表重提）——消除"接口 500 但文档已 PENDING"的悬挂态。
3. 索引任务加心跳字段（`updated_at`），补偿任务只重提超时（如 10 分钟无更新）的 INDEXING 记录，避免重复索引。

## 4. 交付拆分

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| 批次 1 | 专用 executor + BaseAgent 同线程步执行 | 压测：20 并发 manus 会话，无互卡，超载请求排队/429 |
| 批次 2 | 会话互斥 + 409 事件 | 同会话并发第二个请求立即收到提示 |
| 批次 3 | 整轮原子落库 + sequence 原子化 | 并发集成测试：同会话 10 轮并发提交，历史顺序正确 |
| 批次 4 | 索引队列扩容 + 补偿扫表 | 20 个并发上传全部最终 READY 或显式 FAILED |

## 5. 风险与决策点

- `step()` 改同线程后，"步骤级超时"需要看门狗实现，行为与现有 `get(timeout)` 等价但需测试覆盖（现有 `BaseAgentTerminationTest` 是现成基线）。
- 单机锁方案前提是单实例部署（当前 compose 即单实例）；若将来水平扩容，会话互斥与提交幂等需迁到数据库 advisory lock / Redis，接口设计保持不变。
- `MAX(sequence)+1` 改 DB 序列需要迁移存量数据（每 run 取 MAX 建序列起点）。
