# 会话摘要：2026-09-05 安全与可靠性加固

> 状态：已完成并部署到本地环境。此摘要是该阶段工作的唯一交接记录。

## 本阶段做了什么

起点：对仓库做了安全/RAG/编排三个方向深度审计（约 40 项问题，证据保留在 `design/plans/` 各方案内），随后按方案顺序实施：

1. **方案 01 认证与归属**：Spring Security + JWT（jjwt 0.12.6）；`app_user` 表；`CurrentActor` 贯穿全部端点；知识库/Skill 保存/MCP 管理 ADMIN 门禁；废弃 `X-Tenant-Id` 头与 `tenantId` 参数；前端新增 `Login.vue` + 路由守卫 + fetch-SSE（带认证头）+ 图片 blob 授权加载。
2. **方案 02 工具沙箱**：`ToolSandbox`（会话级目录 `temp/tools/{会话id}/`，normalize+startsWith+toRealPath 防穿越/软链）；`UrlAccessPolicy`（DNS 解析后拦私网/loopback/169.254 等，供下载/抓取/MCP 注册共用）；终端工具默认关闭（`app.tools.terminal-enabled=true` 开白名单版）；`ToolFactory` 取代 ToolRegistration 按会话构建工具；manus 生成文件按会话隔离下载。
3. **方案 03 流式可靠性**：消息三态 `IN_PROGRESS/COMPLETED/INTERRUPTED`；USER 先行落库 + `doFinally` 兜底写 ASSISTANT（中断保留部分内容）；manus 断开 `stopForClientDisconnect`；`clientRequestId` 幂等（预检 409 + 唯一索引）；前端断线不删消息、1.5s 后从服务端恢复。
4. **方案 04 并发模型**：`agentExecutor`/`ragExecutor` 专用线程池（`AgentExecutorConfig`）；BaseAgent 同线程步执行（根治 commonPool 饥饿）；`ConversationLockManager` 会话互斥（409 `ConversationBusyException`）；执行事件 `INSERT..SELECT..RETURNING` + 冲突重试；索引队列 core2/max4/queue100 + 429 映射。
5. **方案 05（部分）**：同名知识文档幂等替换 + `DELETE /ai/agent-knowledge/documents/{id}`；私有库检索 3s 超时（`app.agent.rag.timeout-ms`）；`LoveRagTrace` 加 `degraded` 字段；`QueryRewriter` 接入 love 主检索链路（1.2s 预算回退）；`ToolCallAgent.think()` 重试 3 次、不再返回陈旧回答/写错误进记忆；历史上下文 8000 字符预算（Love/Travel）。
6. **P1/P2 追加加固**（应对"答一半停/无响应"）：
   - WebSearchTool 两分支 10s 超时；DashScope SDK 超时（`DashScopeClientConfig`：connect 10s / write 30s / read 60s 字节间空闲，**不设总时长**否则掐断长回答）。
   - BaseAgent 步骤看门狗（共享守护 `ScheduledExecutorService`，对齐 5min 总时限）。
   - SSE 心跳 15s：Flux 流用 `withHeartbeat`（`publish` 共享防止主流双订阅）；manus 用 `HEARTBEAT_SCHEDULER` 发 SseEmitter。
   - 前端 `connectSSE` 看门狗：45s 无事件（含 ping）主动断开走恢复；`closed` 标志防 onerror 双触发。
   - `util/ErrorMessages` 分类（限流/审查/鉴权/超时）→ `generation-error` 事件，love/travel/test/manus 四链路 + BaseAgent catch 透传。
   - **断点续写**（仅 love）：`continueFromMessageId` → `getInterruptedAssistantDraft`（归属+状态校验）→ 草稿尾部 200 字做检索 query → 续写 prompt（草稿注入 system + 明确续写指令）→ `appendToAssistantReply`（`content || ?` + `WHERE status='INTERRUPTED'` 原子追加防并发）→ 状态转 COMPLETED；前端 ChatRoom「继续生成 ▸」按钮（`msg.status === 'INTERRUPTED'`）。

## 数据库状态

Flyway 全部应用成功（连接：`docker exec yu-ai-agent-postgres psql -U zwx_agent -d zwx_agent`，注意用户是 `zwx_agent` 不是 postgres）：

- V4 `add_authentication_and_ownership`：`app_user` 表；`love_conversation`/`agent_conversation` 加 `tenant_id`+`user_id`（存量回填 `legacy`）
- V5 `add_message_status_and_idempotency`：两张消息表加 `status`、`client_request_id`（部分唯一索引）

## 验证结果（实测通过）

匿名 401 / 错密码 401 / 跨用户读会话 403 / 普通用户管理接口 403 / admin 200；正常对话落库；幂等重放 409；同会话并发 409；2s 断线 → ASSISTANT INTERRUPTED 落库；续写 132→491 字符转 COMPLETED；心跳 3s 间隔实测 6 ping（已改回 15s）；20 个单元测试全绿（含路径穿越/SSRF/白名单安全用例）。

## 环境与账号

- 后端 launchctl `com.zwx.yu-ai-agent.backend`（health ok）；前端 `com.zwx.yu-ai-agent.frontend`；重启用 `launchctl kickstart -k`。
- 测试账号：`admin/admin123`（ADMIN，首次启动引导创建，生产必须 `APP_SECURITY_ADMIN_PASSWORD` 覆盖）、`smokeuser/smoke123`（USER）。
- 修复过的坑：pgjdbc 对 `prepareStatement(sql, String[]{...})` 生成键支持有问题（INSERT 后改用 SELECT max id）；Spring Security 6 默认拦截 ASYNC dispatch → SecurityConfig 必须放行 `DispatcherType.ASYNC/ERROR`，否则 Flux SSE 全挂。

## 遗留待做

- git 提交（改动量大，未 commit）
- RAG：混合检索 + rerank、评测集
- 编排：四阶段任务状态机（Plan/Execute/Verify/Deliver）、run 持久化恢复、`DelegateTool` 子智能体
- 长期记忆（`user_memory` 表 + 提取/注入/编辑）
- 续写扩展到 travel/manus；多实例部署前把会话锁换分布式锁
- 生产 `.env` 增加 `JWT_SECRET`、`APP_SECURITY_ADMIN_PASSWORD`
