# 工作 Agent 整改进度（A0/A1/A2 完成，2026-11-05）

依据 `docs/design/2026-11-05-work-agent-remediation-plan.md` 分批执行；本摘要跨多批次滚动更新。

## 已完成批次

### A0（提交 10b23d1）
- 记忆跨用户注入修复：V34 索引改按用户部分唯一；factsFor 强制 userId；身份链路（AgentRunIdentity/AgentRunContext/graph RunContext）贯通 userId。bug 记录 docs/bugs/bug-2026-11-05-memory-user-isolation.txt。
- 授权闭锁：ToolConfirmationService.decide 持久化失败抛错且不同步内存。bug 记录 docs/bugs/bug-2026-11-05-confirmation-memory-approval.txt。

### A1（提交 5fd51ee）
- 迁移 V35-V39（expand-only）：agent_run 18 列执行底座扩展 + 事件序号回填 + token 计量升 BIGINT + 复合所有权候选键；assignment 租约列；event sequence→BIGINT + 结构化列 + dedupe 索引；tool_call 调用身份列 + legacy 回填；4 张新表 agent_run_step/input/checkpoint/evidence（复合 FK 防跨租户）。
- store 包四个读写服务（DAG 校验/CAS/幂等消费/检查点哈希/证据验证），13 项测试。
- V33 唯一键未删（按方案切换条件留待旧引擎退役）。

### A2（提交 beebca0）
- 迁移 V40：agent_tool_confirmation 操作级授权列（operation_key/scope/consumed_at 等）。
- AgentRunStateService（engine_version=2 状态机）：启动幂等、SKIP LOCKED 认领 + fencing、租约心跳/回收（有界重试→BLOCKED）、control_state 暂停/取消、排队期控制请求直接终态化、终态 CAS、同任务续答（答复并入 history_context，不重写 input）。
- AgentRunDispatcher：定时认领派发、会话槽占用退回、安全点停机（ManusRunRequest.controlStopCheck）、终态经 CAS 回写、SSE 观察通道化。
- 事件：AgentExecutionTraceService 改 run.event_seq 原子分配 + 结构化列 + dedupe；新增 listAfter 游标分页。
- 新接口（ManusAgentController）：POST /manus/conversations/{id}/runs（202 幂等）、GET snapshot、GET events、POST pause、POST cancel（按引擎分流）、POST resume、POST inputs/{inputId}/answer；AskHuman 落 QUESTION 输入。
- AgentRun 记录增加 engineVersion 字段。

## 验证

- A 批次相关 130 项测试全绿（状态机 CAS/认领 SQL/事件分配器/DAG/幂等消费/记忆隔离/授权闭锁）。
- 本地真实链路冒烟（v2 接口 + dev JWT）：启动幂等重放同 runId、排队取消→STOPPED/INTERRUPTED 终态、run.created 事件回放 cursor=1；一次意外完整执行（派发→模型回复→消息落库）反向验证了执行链路。冒烟数据已从共享库删除。
- 迁移 V34-V40 均已在共享库实际应用（本地后端经 15432 SSH 隧道连的是服务器生产库）。

## 关键环境事实（后续批次必须知道）

- **本地开发后端连接的是云服务器生产库**（SSH 隧道 127.0.0.1:15432 → 1.13.152.215:5432，容器 aster-postgres-1）。本地重启后端 = 生产库立即执行新迁移：只允许 expand-only 迁移在本地直接验证，破坏性迁移必须先在备份库演练。
- 本地 JWT 用 application.yml 的 dev 默认密钥；JWT uid 必须存在于 app_user 表（JwtAuthFilter.sessionUserExists）。冒烟用 admin 用户（f10dfb01-…，tenant default）签名 token。
- 服务器上 backend/frontend 容器当前为 0.6.0（本日部署），不含 A0-A2 代码；部署 A 批次需走 DEPLOYMENT.md 打包流程（构建 backend 镜像即可，前端无改动）。

## 未完成（后续批次，按依赖序）

- A2 收尾项（已明确推迟）：invocation_key/operation_key 实际写入账本（列与索引已就绪）与 PENDING 有界等待；确认粒度从 (run,tool) 切到 operation_key+参数指纹；V33 约束删除（旧引擎退役后）；ManusRunRequest 装配补 MCP 工具与知识检索（v2 装配当前为默认模型+项目工具+会话历史）。
- B1：命令会话、上下文引用、STEER/NEXT_TURN 输入队列接入引擎。
- B2：agent_artifact_change/restore + 轮次审阅（最高风险：撤销的崩溃补偿）。
- C1：goal/iteration + profile/version；C2：scheduled_task/run（定时任务规划文档的前置已就绪）。
- D：浏览器会话、通知、快捷指令、Wiki、记忆治理（每项独立启用）。
- 前端迁移（§10）：SuperAgent.vue 拆 useRunSession/useRunEvents、ChatRoom 共用 run store——待后端 B1 后统一做。

## 环境注意事项

- 本地后端 launchctl 运行中，当前包含 A0-A2；前端未动。
- application-local.yml 未读取敏感值、未修改；JWT 冒烟仅使用仓库内公开 dev 默认密钥与只读用户元数据。
- 冒烟产生的两段会话/运行数据已精确删除（级联验证 0 残留）。
