# 会话摘要：引用定位到表格内容区域（2026-10-23）

## 改动内容

### 数据层

- 新增 `V19__add_knowledge_source_block.sql` 与 `agent_knowledge_block` 表：保存文档版本内的 text/table/figure/equation 来源块，包含 blockId、页码、bbox、caption、HTML、结构化 JSON、图片 objectKey。
- MinerU 完整结果包落 OSS：`knowledge/{tenant}/{agent}/{documentId}/mineru/`，并写 manifest。

### 解析与切片

- 新增 `HtmlTableParser`：HTML/Markdown 表格统一转 `headers + rows`，复用 rowspan/colspan 展开逻辑。
- 新增 `MineruBlockParser`：解析 `content_list.json`（兼容 `*_content_list.json`、pages 包装），生成 text/table/figure/equation 块。
- 新增 `StructuredKnowledgeChunker`：
  - 文本按章节聚合，写 page/blockId；
  - 表格按 6 行一组切片，写 `rowStart/rowEnd/regionLabel/tableCaption`；
  - 图片先用图注入索引，视觉理解留到批次 2。
- `AgentKnowledgeDocumentService` 接入 block 解析、结果包持久化、来源块落库；没有 content_list 时回退原 Markdown 切片。

### 引用链路

- `AgentKnowledgeChunkPreview` 增加 `displayText/pageNumber/sectionPath/sourceType/regionLabel/legacy/table`。
- 引用接口支持 `chunkId` 找不到时用 `documentId + blockId` 回退到来源块。
- `RagTraceCandidate` 增加 `chunkId/sourceDocumentId/blockId/sourceType/rowStart/rowEnd/regionLabel`，检索摘要遇到 HTML 时做纯文本化。
- 新增前端 `CitationPreview.vue`：真实表格渲染、命中行高亮、区域标签；旧切片降级纯文本并显示“旧索引”。
- `ChatRoom` 引用卡片优先显示 `regionLabel`，不再直接展示原始 HTML。
- 工作台交付区的“引用资料”也改为可点击：复用 `CitationPreview` 与引用接口，卡片显示区域标签并去掉 HTML 摘要；此前该区域只是静态 article，没有绑定点击事件。

## 验证结果

- 后端 `mvn clean test`：210 个测试，0 失败，0 错误，1 跳过。
- 前端 `npm run build` 通过。
- Playwright + mock API：
  - 引用卡片显示“表 2.3.2-1 · 第 12 页 · 第 3-5 行”；
  - 弹窗渲染 8 行真实表格，命中 3 行高亮；
  - 页面无原始 `<table>` 标签。
- 交付区引用卡片点击验证：1 个引用可打开弹窗，区域标签正确，表格命中 3 行高亮。
- V19 已应用到本地 PostgreSQL；新 JAR 已重启，`/api/health` 返回 200。

## 遗留事项

- 旧文档必须点击“重新解析/重建索引”后才会生成来源块和新 metadata；旧引用会先以纯文本降级显示。
- Docling/本地解析路径暂未生成表格来源块，本批次优先覆盖 MinerU + content_list。
- 图片/流程图视觉理解、文本精确高亮、引用快照和评测属于批次 2–4。
- 本轮改动尚未提交。

## 环境注意事项

- 本地后端仍由 `com.zwx.yu-ai-agent.backend` launchctl 任务托管，日志 `/tmp/yu-ai-agent-backend.log`。
- `mvn clean test` 清理了 `target` 下历史重复 class 文件；测试耗时恢复正常。
- 未读取、修改或提交 `src/main/resources/application-local.yml`。
