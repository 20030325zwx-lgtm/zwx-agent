# 会话摘要：2026-09-05 DatabaseQueryTool（只读 SQL 查询工具）

> 状态：已完成、已启用并部署到本地环境（后端已重启，health ok）。支持两类查询：查本应用自己的库 + 按用户提供的连接信息查外部 PostgreSQL/MySQL 库。

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
