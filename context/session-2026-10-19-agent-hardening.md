# 会话摘要：2026-10-19 Agent 安全与运行治理

## 改动内容

- Verifier 失败时不再默认 pass；无法验收的最终结果标注 `[未验收]`。
- ToolGuard 对未登记工具默认拒绝，受管工具参数解析失败关闭；工具拒绝日志只写参数 key/长度。网页抓取拒绝自动重定向，下载逐跳检查，外部 DB 目标执行公网地址预检。
- 执行 trace、ToolCallAgent、LoveApp 和 MyLoggerAdvisor 不再输出原始 prompt、工具参数或模型回答正文，改为事件/长度元数据。
- `ConversationLockManager` 改用 PostgreSQL lease 和 owner token；关闭幂等，Manus 准备/提交异常会释放租约，SSE 多回调通过一次性清理关闭 MCP 与锁。
- Worker 用 FutureTask 保存并取消真实执行任务，并按 conversationId 隔离取消；executor 改 AbortPolicy。Worker 队列满会报告子任务未启动；RAG 队列满沿用检索降级。
- 路线图和面试笔记已按源码更新；新增 V8 会话租约表迁移与 `ConversationLockManagerTest`。

## 验证

- 定向离线回归：73 tests，0 failures/errors。
- 全量 `mvn -o test` 在 `MultiQueryExpanderDemoTest` 的 Spring 上下文组件扫描期间长时间无进展；线程栈显示主线程阻塞在 classpath class 读取。已中止该次挂起运行，不能视为全量测试通过。
- `mvn -o -DskipTests package`：BUILD SUCCESS。
- 后端由 launchctl 重启；Flyway 成功应用 V8；`http://127.0.0.1:8123/api/health` 返回 `ok`，前端 `http://127.0.0.1:3000/` 返回 HTTP 200。
- `git diff --check` 无 whitespace 错误。

## 遗留事项

- 终端仍不是 OS/容器级沙箱。不要仅限制后端容器就宣称安全；建议独立执行服务，控制挂载、网络、身份、系统调用和资源配额。
- MCP 全局工具名 allowlist 不包含 schema/资源级策略；需要管理员逐工具登记风险和授权范围。
- DNS rebinding/解析到连接的竞态仍需 egress proxy 或固定 IP 连接策略解决；公网 DB 校验不代表业务授权，且当前不支持企业内网 DB。
- run 总 deadline 与底层模型 HTTP/MCP/JDBC/子进程取消尚未贯通；MemorySaver 仍不能跨进程恢复。
- 长期记忆查看/编辑/删除/过期/敏感事实治理、全数据生命周期策略、离线 Agent/RAG 评测集、线上 SLO/告警仍待实现。
- 完整 Maven 测试因 SpringBootTest 扫描挂起未完成；后续需定位为何扫描卡住并补可重复的集成测试流程。
- 本轮未提交代码。保留用户未跟踪文件 `design/2026-09-28-deepseek-harness-vs-yu-agent.md` 与 `doc/agent-interview-notes.md`。

## 环境注意事项

- `src/main/resources/application-local.yml` 存在且未读取、编辑、复制或加入 Git。
- PostgreSQL 与 MinIO Docker 容器保持运行。服务使用 launchctl 管理；后端重启前须重新打包。
- 不运行 `git clean`、`git reset --hard` 等清理操作。
