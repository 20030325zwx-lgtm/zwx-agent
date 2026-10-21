# 收敛为超级智能体单入口（2026-10-21）

## 决策

超级智能体已经覆盖对话、图片理解、私有知识库、联网、文件与多步工具调用能力，首页不再并列展示“情感分析大师”“旅游规划专家”“功能测试助手”。产品入口统一为：

- 首页：超级智能体卡片
- `/super-agent`：超级智能体工作台
- `/model-settings`、`/skill-settings`、`/mcp-settings`、`/knowledge-admin`：工作区配置

## 删除范围

- 前端：三个智能体页面、路由、API 调用、首页“对话问答”分组。
- 后端：三个智能体的 App、会话/图片/旧版 RAG、控制器端点与专属测试。
- 数据库 V17：
  - 删除 `love_conversation`、`love_chat_message`、`love_knowledge_index_job`、`love_knowledge_vector`。
  - 清理 `agent_conversation`、`agent_skill_configuration`、`agent_memory_fact`、`agent_knowledge_*` 中 `love/travel/test` 的数据。

## 保留范围

- `agent_*` 表结构与超级智能体数据；`agent_conversation` 等表由超级智能体继续使用。
- 共享的 `LoveKnowledgeReference` 类型保留，作为知识库引用 DTO，避免影响超级智能体的引用展示。
- 工作区导航、模型配置、MCP、知识库与 RAG 检索链路不变。

## 数据备份

迁移前已备份相关表到本机 `/tmp/yu-ai-agent-backup/pre-agent-removal.sql`（不属于仓库）。
