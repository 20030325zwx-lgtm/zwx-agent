# 后端包结构重组（2026-10-21）

## 目标

将原先平铺的顶层包按业务域收敛，降低包数量与单包体积，让入口、智能体、对话、知识库、能力和平台支撑的边界更清晰。

## 新结构

```text
com.zwx.zwxagent
├── agent/
│   ├── core/          BaseAgent、ReActAgent、ToolCallAgent、ZwxManus 等核心代理
│   ├── advisor/       ChatClient 日志与推理 Advisor
│   ├── execution/     run、检查点、工具调用账本、恢复任务
│   ├── graph/         Planner/Workers/Verifier/Aggregator 图编排
│   ├── hook/          工具护栏、记忆、轨迹等 hook
│   └── model/         AgentState
├── chat/
│   ├── conversation/  会话、消息、锁与实例注册
│   └── memory/        长期记忆与文件记忆
├── knowledge/
│   ├── document/      文档上传、解析、Docling、切分与索引
│   ├── retrieval/     RAG 检索、重排、trace 与引用
│   ├── image/         图片理解
│   └── storage/       OSS 对象存储
├── capability/
│   ├── tools/         内置工具与工具守卫
│   ├── mcp/           MCP 服务配置与工具加载
│   └── skills/        Skill 注册、配置与提示词
├── platform/
│   ├── config/        Spring 配置、CORS、线程池
│   ├── security/      JWT、用户、角色与权限
│   ├── settings/      模型配置、密钥加密与动态模型工厂
│   └── workspace/     会话工作区
├── web/
│   └── controller/    REST 与 SSE 入口、异常处理
├── common/           通用常量与错误信息
└── ZwxAgentApplication
```

## 关键调整

- 删除 `demo` 示例包，减少生产代码干扰。
- `rag` 按职责拆为 `knowledge.document`、`knowledge.retrieval`、`knowledge.image`。
- `configuration` 重命名为 `platform.settings`，避免与 `config` 混淆。
- `chatmemory` 与 `memory` 合并到 `chat.memory`。
- `execution` 移入 `agent.execution`，因为它服务于智能体运行与恢复。
- `controller` 移入 `web.controller`；Swagger `packages-to-scan` 同步更新。

## 配置同步

- `META-INF/spring.factories` 中的 `GlobeConfEnvironmentPostProcessor` 更新为新包名。
- `application.yml` 的 Swagger 扫描包更新为 `com.zwx.zwxagent.web.controller`。
- 测试类随之迁移到对应新包，保持包内可见性。

## 验证

- `mvn clean test-compile` 通过。
- 全量 `mvn test`：198 个测试，0 失败，0 错误，1 跳过。
- `mvn clean package -DskipTests` 通过；后端重启后 `/api/health` 返回 `ok`。
