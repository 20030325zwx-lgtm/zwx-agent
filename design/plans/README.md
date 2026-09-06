# 实施方案索引

本目录保存按优先级排列的改造实施方案。问题来源为 2026-09 对仓库的安全审计、RAG 链路分析与编排工程分析。

| 编号 | 方案 | 状态 | 备注 |
| --- | --- | --- | --- |
| [01](./01-auth-and-ownership.md) | 认证与会话归属 | ✅ 已实施（2026-09-05） | Spring Security + JWT；用户表 V4；会话/消息/图片/文件全部绑定身份；管理接口 ADMIN 门禁；前端登录页 + fetch-SSE 鉴权 |
| [02](./02-tool-sandbox-security.md) | 工具安全边界与沙箱 | ✅ 已实施（2026-09-05） | ToolSandbox 会话级工作目录；UrlAccessPolicy 拦截私网/SSRF；终端工具默认关闭（白名单+超时版本保留）；MCP 私网校验+单服务故障隔离；生成文件按会话隔离下载 |
| [03](./03-streaming-reliability.md) | 流式链路可靠性 | ✅ 已实施（2026-09-05） | USER 先行落库 + doFinally 兜底（INTERRUPTED 状态）；manus 客户端断开即停；travel 中断保留执行轨迹；clientRequestId 幂等（409）；前端断线保留内容并从服务端恢复 |
| [04](./04-concurrency-and-execution.md) | 并发模型与执行正确性 | ✅ 已实施（2026-09-05） | agentExecutor/ragExecutor 专用线程池；BaseAgent 同线程步执行；会话级互斥（409）；执行事件序号原子化+重试；索引队列扩容+429 |
| [05](./05-agent-architecture-rag-memory.md) | 智能体架构演进、RAG 质量与长期记忆 | ◐ 部分实施（2026-09-05） | 已完成：文档幂等上传/删除端点、私有库检索超时、RAG degraded 标记、QueryRewriter 接入主链路（带预算）、ToolCallAgent think 重试修复、历史上下文字符预算。待做：混合检索+rerank、RAG 评测集、四阶段状态机、run 持久化恢复、长期记忆 |
| [07](./07-knowledge-document-versioning.md) | 知识库文档版本与有效性治理 | ◌ 设计提案（2026-09-07） | 规划 logical_key、版本链、ACTIVE/ARCHIVED 生命周期、原子发布、重复检测、冲突处理与版本感知检索；尚未实施 |

排序原则：先封住"上线即事故"的安全与数据归属问题，再解决日常使用中的丢消息、卡死、串话，最后扩展智能体能力。

实现细节以源码为准；方案中的文件行号基于 2026-09-05 的仓库状态。

