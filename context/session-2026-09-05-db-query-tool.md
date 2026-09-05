# 会话摘要：2026-09-05 DatabaseQueryTool + 多智能体 Graph 重构

> 状态：两项工作均已完成并部署到本地环境（health ok）。
> ①只读 SQL 工具（查自身库 + 用户提供的 PostgreSQL/MySQL 外部库）；②manus 链路重构为 Spring AI Alibaba Graph 多智能体状态机（设计文档 `design/plans/06-multi-agent-graph.md`）。

## 改动内容

需求：给智能体增加一个能查数据库、执行 SQL 的内置工具（此前没有，仅有 6 类内置工具 + MCP 动态工具）。

1. **新增 `src/main/java/com/zwx/zwxagent/tools/DatabaseQueryTool.java`**
   - `executeDatabaseQuery(sql)`：只读执行 SELECT/WITH 查询，结果以 `行数 + 表头 + 行` 文本返回（列用 ` | ` 分隔）。
   - 安全部校验（validate，包级可见便于测试）：
     - 仅允许单条语句：拒绝多条 `;`、拒绝 `--` 与 `/* */` 注释；
     - 首词必须是 `select`/`with`；全词扫描禁止关键字（insert/update/delete/drop/alter/create/truncate/grant/revoke/call/copy/vacuum/merge/lock/into/set/reset 等），阻断数据修改 CTE（`WITH x AS (INSERT...) SELECT`）；
     - 禁止危险函数名（dblink/lo_import/pg_read_file/pg_sleep 等）；
     - 表名提取（extractTables）：手工扫描 FROM/JOIN 子句，支持 schema 限定名、逗号多表、别名（含 AS）、LATERAL/ONLY、跳过表函数（`f(...)`）与平衡括号；白名单/黑名单按**最后一段**表名匹配；
     - 默认黑名单 `app_user`、`flyway_schema_history`（可用 `app.tools.db-query-denied-tables` 追加）。
   - 执行期防线：连接 `setAutoCommit(false)` + `setReadOnly(true)`（PostgreSQL 服务端只读事务兜底）、`queryTimeout`、`maxRows`，执行后 `rollback()`。
2. **修改 `ToolFactory`**：注入 `DataSource` 与配置项；按开关向工具列表追加工具。
   - 配置键：`app.tools.db-query-enabled`（默认 false，查本应用库）、`db-query-external-enabled`（默认 false，查外部库）、`db-query-table-allowlist`（空=除黑名单外全部可查）、`db-query-denied-tables`（默认 app_user/flyway_schema_history）、`db-query-max-rows`（默认 20）、`db-query-timeout-seconds`（默认 5）。
3. **新增 `src/main/java/com/zwx/zwxagent/tools/ExternalDatabaseQueryTool.java`**
   - `queryExternalDatabase(databaseType, host, port, database, username, password, sql)`：按用户提供连接信息经 `DriverManager` 直连外部库执行只读 SELECT；类型支持 postgresql/mysql（默认端口 5432/3306）；URL 带连接与 socket 超时；登录超时 = query timeout。
   - 复用 `DatabaseQueryTool.validateSyntax`（static）与 `formatResult`（static）；外部库**不做**表白/黑名单校验（异构 schema）。
4. **pom.xml**：新增 `com.mysql:mysql-connector-j`（runtime，Spring Boot BOM 管理版本）。
5. **application-local.yml**：在既有 `app:` 块内新增 `tools:` 段，开启 `db-query-enabled` 与 `db-query-external-enabled`（其余配置键均未动）。
6. **测试**：`DatabaseQueryToolTest`（10 例）+ `ExternalDatabaseQueryToolTest`（5 例：不支持类型、缺 host/database、非 SELECT、多语句、buildUrl）。

## 踩过的坑（重要）

- `extractTables` 重构时**丢了 `index = identifierEnd[1]` 赋值**，导致 `index` 停在标识符起点，`LATERAL f(...)` 中 `f` 被误当表名/别名链错位。通过字节码（javap -c）对照源码才发现。教训：重构赋值链时逐行 diff。
- macOS `strings` 命令会把 .class 当 Mach-O 报 malformed，验证 class 内容用 `javap -c` 而不是 strings。
- LoveAppTest(4) / PgVectorVectorStoreConfigTest(1) 共 5 个报错为**改动前已存在**的环境/数据依赖失败（FK 违规、bean 注入），已用 `git stash push -u` 回滚验证过，与本工具无关。

## 验证结果（实测通过）

- `mvn test -Dtest='DatabaseQueryToolTest,ExternalDatabaseQueryToolTest'` 15/15 绿；全量 `mvn test` 42 跑 / 5 错（LoveAppTest×4、PgVectorVectorStoreConfigTest×1，`git stash push -u` 回滚验证为**改动前已存在**的环境/数据依赖失败，与本工具无关）。
- `mvn -DskipTests package` 成功；`launchctl kickstart -k` 重启后端，`GET /api/health` → ok，日志无异常。
- 真实连库验证（/tmp 临时程序，凭据从 yml 读取、不落日志）：外部工具连 127.0.0.1:5432/zwx_agent 执行 `SELECT tablename FROM pg_tables ... LIMIT 5` 返回 5 张表、格式正确；`DELETE FROM app_user` 在执行前被 `validateSyntax` 拦截（"only read-only SELECT queries are allowed"）。

## 安全注意事项（需要让用户知道）

- 外部查询的**密码会出现在对话上下文/执行轨迹里**（模型 tool arguments 明文），且本工具对目标主机不做私网限制（本就是给用户自带库用的）。生产环境如需启用，建议限制出口网段或走代理，并评估轨迹泄露面。
- 本应用库查询默认黑名单了 `app_user`（密码哈希）与 `flyway_schema_history`。

---

# 追加批次：多智能体 Graph 重构（同日晚些时候完成）

## 改动内容

需求：多 agent 协作——一个 manus 对话里主 agent 调度多个子 agent 并行干活。选定 Spring AI Alibaba Graph 落地。

1. **pom.xml**：新增 `spring-ai-alibaba-graph-core`（版本由既有 `spring-ai-alibaba-bom:1.0.0.2` 管理，无需写版本号）。
2. **新增 `agent/graph/` 包**：
   - `ManusGraphOrchestrator`（@Service）：@PostConstruct 建图并编译；图：`START→planner→workers→verifier→(conditional: revise→workers / pass→aggregator)→END`；`setMaxIterations(16)` 防死循环；`runStream(request, executor)` 复刻 BaseAgent SSE 契约（activity 事件、最终回答、[DONE]、generation-error）；`mermaidDiagram()` 输出编译图。
   - `PlannerNode`：拆解为 ≤4 个子任务（title/role/detail JSON），解析失败回退单个 general。
   - `WorkersNode`：按角色过滤工具子集后**并行**跑子 agent（每个子 agent = 加固过的 `ToolCallAgent`，≤8 步，graphWorkerExecutor 线程池）；活动流水含工具结果原文（manus 附件提取正则依赖它）。
   - `VerifierNode`：裁决 pass/revise+反馈；`MAX_REVISIONS=1`，质检器自身失败按通过处理。
   - `AggregatorNode`：中文交付总结，失败回退结果拼接。
   - `WorkerRole`（researcher/author/analyst/general + 工具白名单）、`PlanStep`、`PlanJsonParser`、`GraphState`（状态键+Append/Replace 策略）、`RunContext`（工具/知识/停止信号）。
3. **AiController.doChatWithManus**：ZwxManus 换成 orchestrator（锁、心跳、MCP 生命周期、saveCompletedTurn、附件逻辑全部保留）；历史拼为文本经 state.history 传入。`ZwxManus` 类保留未引用，作回滚开关。
4. **AgentExecutorConfig**：新增 `graphWorkerExecutor`（core6/max12/queue50，`app.graph.executor.*` 可配）。
5. **测试**：`agent/graph/` 16 个用例（计划解析回退/截断、角色工具过滤、verifier 路由与返工预算、建图成功+Mermaid 含全部节点）。全绿。

## 踩坑

- `WorkerRole` 工具白名单大小写必须与过滤逻辑一致（构造时统一 toLowerCase，否则过滤结果为空）。
- graph-core 1.0.0.2 的 `AsyncGenerator` 实现 `Iterable`，可直接 for-each `compiledGraph.stream(inputs)`。
- macOS `strings` 不能看 class 文件（按 Mach-O 解析报 malformed），用 `javap -c`。
- graph-core 未暴露 fan-out 边 API，workers 并行在节点内部用线程池实现（见设计文档 §2）。

## 验证结果

- `mvn test -Dtest='com.zwx.zwxagent.agent.graph.*,ZwxManusTest,BaseAgentTerminationTest'` 全绿（ZwxManusTest 57s 走了真实 LLM）。
- 打包、`launchctl kickstart -k` 重启，health ok；启动日志出现「manus 多智能体图编译完成」。
- 真实多 agent E2E（前端 manus 发起含多子任务请求）待人工验证。

## 遗留

- checkpoint 持久化（RedisSaver）+ `threadId=conversationId` 断线恢复；human-in-the-loop（interruptBefore/resume）
- 子任务依赖 DAG（现为纯并行）；前端活动面板按 worker 分组
- 上一批次的 git 提交仍待用户指示（现在包含 SQL 工具 + graph 重构两批改动）

## E2E 验证结果（真实 manus 会话，2026-09-05 晚）

通过 API（admin 登录 → POST /ai/manus/conversations → GET /ai/manus/chat SSE）验证两轮：

1. **双子任务请求**（查表数量 + 联网搜 PG17 特性）：
   - planner 正确拆成 analyst + researcher 两个子任务，角色分配精准
   - workers 并行执行（日志确认 graph-worker-1/2 同时活跃）：analyst 调 executeDatabaseQuery 返回 13 张表；researcher scrapeWebPage 抓官方文档输出完整特性整理
   - verifier「质检通过」→ aggregator 合并交付 → [DONE]
2. **单子任务请求**（查表总结）：planner 只拆 1 个任务（"简单请求不过度拆分"规则生效），全链路正常。

### E2E 发现并修复的两个问题

- **NPE 崩溃**：graph-core 1.0.0.2 在未配 SaverConfig 时 stream() 无条件调用 checkpointSaver().get() → NPE。修复：注册 `MemorySaver`（SaverConfig.builder().type(MEMORY).register(...)），stream 传 `RunnableConfig.threadId=conversationId`。Checkpoint 按引用存 Map 不序列化，RunContext 放 state 安全。
- **aggregator 幻觉**：结果里没有 PDF 却输出"已生成 PDF 可下载"（原 prompt 第 4 条诱导）。修复：改为严格禁止——仅当子任务结果明确提到已生成文件才可提及，禁止输出任何下载链接。已复验（grep 下载|PDF 计数 0）。

### 结论

Graph 多智能体链路可正式使用；checkpoint 已随 saver 修复顺带启用（MemorySaver），断线恢复（getStateHistory/resume）具备条件但尚未接入交互。

## 追加：执行过程展示优化（结构化 SSE 事件）

问题：前端活动面板满屏"正在处理任务"——旧链路推的是原始 Step 日志，前端 activityLabel 正则匹配不上就显示兜底文案。

改动：
- 后端：新增 `ActivityEvent(phase, agent, summary)` + state 键 `sse_events`（Append）；各节点产出语义事件（规划器/调研员/撰写员/分析员/通用助理/质检员 + 一句话摘要），`GraphWorker`（ToolCallAgent 子类）逐步把工具执行转成"中文工具名 → 结果摘要"；编排器差量序列化为 JSON 推 `activity` 事件。原始 `activities` 留痕仅用于附件提取，不再直推前端。
- 前端 `SuperAgent.vue`：`parseActivity` 解析 JSON 渲染「角色 · 摘要」，解析失败回退旧 activityLabel。
- 注意：`Map.of` 最多 10 对，状态策略注册改用 `Map.ofEntries`。

E2E 复验通过（双子任务请求，事件流见设计文档 §4）。graph 包 16 测试全绿，已部署（health ok）。前端 vite HMR 自动生效，无需重启。
