# 会话摘要：收敛为超级智能体单入口（2026-10-21）

## 改动内容

- 首页移除“对话问答”分组，只保留超级智能体卡片；Hero 文案改为统一能力描述。
- 前端删除：
  - `LoveMaster.vue`、`TravelPlanner.vue`、`TestAgent.vue`、`TravelPlanner 2.vue`
  - 对应路由、API 调用、`config/agents.js` 中的三个智能体定义
  - `chatWithLoveApp` 等 Love/Travel/Test 请求函数
- 后端删除：
  - `LoveApp`、`LoveVision*`、`LoveRag*`、`LoveKnowledge*`、`LoveConversation*`、`LoveImage*`、`PostgresChatMemory`、`AgentRegistry`
  - `TravelPlannerApp`、`TestAgentApp`
  - `AiController` 中对应的会话、图片、知识库索引、SSE 与执行事件端点
  - 两个专属测试类
- 共享代码保留：
  - `AgentConversationService`、`AgentKnowledge*`、`AgentRun*`、`AgentExecutionTraceService` 继续服务超级智能体。
  - `LoveKnowledgeReference` 作为共享引用 DTO 保留。
- 数据库 V17 `remove_legacy_chat_agents`：
  - 删除 4 张 `love_*` 表。
  - 清理 `agent_*` 表中 `love/travel/test` 的会话、Skill 配置、记忆与知识库数据。
  - 共享 `agent_*` 表结构保留，超级智能体数据未动。
- 文档：
  - README 顶部介绍、能力列表、路由说明更新为单入口。
  - ROADMAP 智能体编排项改为超级智能体。
  - 新增 `design/2026-10-21-single-super-agent.md`。

## 验证结果

- 迁移前备份相关表到 `/tmp/yu-ai-agent-backup/pre-agent-removal.sql`（12MB，未入库）。
- `mvn clean package -DskipTests` 成功；后端重启后 `/api/health` 返回 `ok`。
- Flyway V17 已应用；`love_*` 表数量为 0；`agent_conversation` 仅剩 `super`，`agent_knowledge_document`/`vector` 仅剩 `super` 数据。
- 前端 `npm run build` 成功。
- 首页改为 bento 式工作区入口：左侧超级智能体主卡（流程、CTA、动态光晕），右侧四张能力卡（多步规划/工具调用/知识库/图片理解），不再使用传统列表。
- 超级智能体真实 SSE 对话验证通过（返回 OK）。
- 全量 `mvn test`：199 个测试，0 失败，0 错误，1 跳过。
- 知识库默认仍为超级智能体，主题保持蓝色。

## 遗留事项

- Skill 页面仍保留，但超级智能体当前没有注册 Skill，页面会显示空状态；后续可决定是否移除该入口或把工具能力接入 Skill。
- 本轮改动尚未提交。
- 备份文件只在本机 `/tmp`，如需长期保留请自行转存。
