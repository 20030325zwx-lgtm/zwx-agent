# 会话摘要：知识库文件删除（2026-10-23）

## 改动内容

- 知识库文件列表新增删除按钮：
  - 主行删除整个逻辑文件（全部版本）；
  - 历史版本行可单独删除该版本。
- 删除前使用应用内 `ConfirmDialog` 二次确认，并提示会一并删除切片和来源块。
- 正在处理中的版本禁止删除，避免与异步索引任务竞争。
- 后端 `deleteDocument` 清理范围补齐：
  - PGVector 切片；
  - `agent_knowledge_block` 来源块；
  - 文档记录；
  - 原始文件对象；
  - MinerU 结果包目录 `knowledge/{tenant}/{agent}/{documentId}/mineru/`。
- 新增 `deleteAgentKnowledgeDocument` 前端 API 和 `AgentKnowledgeDocumentDeleteTest`。

## 验证结果

- 后端 214 个测试通过（1 跳过）。
- 前端 `npm run build` 通过。
- Playwright + mock：
  - 主行显示 1 个删除按钮；
  - 确认弹窗文案正确；
  - 确认后发出 `DELETE /ai/agent-knowledge/documents/doc-1?agentKey=super`；
  - 文档列表刷新为空。
- 后端已重新打包并重启，健康检查 200。

## 遗留事项

- 本轮改动尚未提交。
- 删除活跃版本后不会自动把旧版本提升为 ACTIVE；如需保留业务连续性，后续可增加自动晋升策略。
