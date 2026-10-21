# 会话摘要：知识库重建索引生命周期修复（2026-10-20）

## 改动

- `AgentKnowledgeDocumentService.indexDocument` 成功后不再无条件写 `lifecycle_status=READY`：
  - 新版本 `INDEXING` → `READY`，随后自动发布；
  - 已生效 `ACTIVE` / 已归档 `ARCHIVED` 重建索引后保持原生命周期。
- 新增发布接口：`POST /api/ai/agent-knowledge/documents/{documentId}/publish?agentKey=...`。
- 知识库管理页面在选中 `READY` 文档时显示“发布待发布版本”按钮。
- 前端 API 新增 `publishAgentKnowledgeDocument`。
- 新增设计文档 `docs/design/2026-10-20-knowledge-reindex-lifecycle.md`，更新 README 与 design 索引。

## 验证

- 简历文档 `朱文轩-Java后端开发工程师-简历.pdf`：
  - publish 后 `status=READY`、`lifecycleStatus=ACTIVE`、10 chunks；
  - 再次 reindex 后仍为 `READY/ACTIVE`，不再变成“待发布”。
- 前端 `npm run build` 通过；后端重启后 `/api/health` 返回 `ok`。

## 遗留

- 本轮改动尚未提交。
- `include_converted_doc` 大文档响应体优化、简历专用 chunker 仍待后续。
