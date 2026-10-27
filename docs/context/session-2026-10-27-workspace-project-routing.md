# 会话交接：工作区项目识别与本地优先路由（2026-10-27）

## 本次改动

- 复核真实会话 `0525c412-f024-4b5d-bbcd-84031443b2c9`：项目 `agent` 正确绑定 `/Users/zhuwenxuan/Desktop/agent`，Run 为 DIRECT；`zwx-resume-site` 实际存在。
- `listProjectTree` 改为按路径深度优先排序，保证一级目录不会被前序目录的深层文件挤出 500 项上限。
- `searchProjectCode` 在运行环境找不到 `rg` 时自动降级为 Java 正则搜索；跳过敏感文件、符号链接、生成目录和超大文件，保留 100 条/输出长度上限。
- 新增 `WorkspaceRequestDetector`，识别“这个项目/工作区/源码”等明确短语及 `aster-mcp`、`zwx-resume-site` 这类代码式名称。
- 绑定工作区且命中上述信号时不允许闲聊直达；Planner 确定性生成 coder 单任务，要求先检查精确本地路径，不先联网猜测同名项目。

## 验证结果

- 定向测试 `ProjectCodeReadToolTest,WorkspaceRequestDetectorTest,PlannerNodeKnowledgeRoutingTest` 通过。
- `mvn clean test`：310 通过、0 失败、1 跳过。
- 首次增量全量测试被 `target/classes` 中 iCloud 生成的 ` 2.class` 冲突副本拖慢；`mvn clean test` 清理生成物后正常完成，源码无相关异常。
- `mvn -DskipTests package` 成功，JAR 完整性检查通过；本地后端已重启，`GET /api/health` 返回 `ok`。

## 环境与遗留

- 未修改 Flyway、数据库数据或 `application-local.yml`。
- 用户已有的 V17 修改与 `release/` 保持未触碰。
- 当前本地后端已加载本次修复。
