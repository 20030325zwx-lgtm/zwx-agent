# 会话摘要：2026-10-19 RAG 检索过程人工审阅

## 改动内容

- RAG 每轮返回 trace：原始查询、向量与词法候选数量、融合候选和最终选中数、候选来源/页码/chunk 摘要、各通道排名和分数、向量/词法/rerank/总耗时。
- Travel、Test、Super Agent 的 SSE 新增 `rag-trace` 事件；超级 Agent 在回答前向 SSE 发送 trace，并持久化在助手消息上。
- 三类 Agent 历史消息 API 返回 `rag_retrieval_trace`；前端回答后显示“查看检索过程”，允许对候选选择相关、部分相关、不相关。
- 人工判断写入消息 trace 的 `judgments` 对象，验证消息归属当前用户、Agent、候选 ID；历史消息回显已有判断。
- V10 migration 新增 `agent_chat_message.rag_retrieval_trace` JSONB 字段和 GIN 索引。

## 验证

- RAG 定向测试通过：`AgentKnowledgeRagServiceVersionFilterTest`、`MyTokenTextSplitterTest`、`RagEvaluationMetricsTest`。
- `mvn -q -DskipTests package` 通过。
- 前端 `npm run build` 通过。
- `git diff --check` 通过。
- 本地服务已重启，V10 成功应用，`GET /api/health` 返回 `ok`。

## 遗留事项

- 人工相关性标签已持久化，但尚未提供标注聚合、导出为 JSONL 评测集或标注一致性分析。
- 当前检索面板覆盖私有 RAG 的 Travel、Test、Super Agent；Love Agent 使用的内置情感知识库仍是现有独立 trace 展示。
- 仍需用实际业务资料跑端到端问答并观察标注结果；合成奖金制度不会自动上传到任何 Agent 知识库。
- 未提交本轮改动。保留用户已有未跟踪设计文件 `docs/design/2026-09-28-deepseek-harness-vs-yu-agent.md`。

## 环境注意事项

- `src/main/resources/application-local.yml` 未读取、编辑或提交。
- 本地数据库为 OrbStack PostgreSQL；后端仍由 launchctl 管理。
