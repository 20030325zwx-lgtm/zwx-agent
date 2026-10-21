# 会话摘要：Docling Serve 接入知识库入库（2026-10-20）

## 改动内容

- 设计文档：`docs/design/2026-10-20-docling-serve-integration.md`。
- 新增：
  - `DoclingServeClient`：multipart 调用 `POST /v1/chunk/hybrid/file`，连接超时 3s、读取超时 180s。
  - `DoclingServeChunkingService`：校验 chunks，映射标题、页码、token、doc items、Docling metadata。
  - `DoclingChunk` / `DoclingChunkResponse` / `DoclingServeException`。
- `AgentKnowledgeDocumentService.indexDocument`：
  - `.md/.txt` 继续走本地 `DocumentParsingModule + MyTokenTextSplitter`；
  - PDF/DOC/DOCX/XLS/XLSX/PPT/PPTX 优先调用 Docling；
  - Docling 失败且 `fallback-to-local=true` 时回退本地解析/切分；
  - 只记录文件名、类型、失败原因，不记录文档内容。
- `AgentKnowledgeDocumentService.getDocument`：
  - 本地解析失败时不再直接 500，使用已入库 chunks 拼接详情原文。
- `application.yml` 新增 `app.document-parser.docling.*` 配置：
  - `enabled=true`
  - `base-url=http://127.0.0.1:5001`
  - `timeout-seconds=180`
  - `fallback-to-local=true`
  - `formats=pdf,doc,docx,xls,xlsx,ppt,pptx`
  - `do-ocr=true`、`do-table-structure=true`、`table-mode=accurate`
- 更新 `README.md`、`docs/design/README.md`、`docs/ROADMAP.md`。

## 验证

- 单元测试通过：
  - `DoclingServeChunkingServiceTest`（metadata 映射、空 chunks）
  - `AgentKnowledgeDocumentServiceUploadTest`
  - `MyTokenTextSplitterTest`
  - `AgentKnowledgeRagServiceVersionFilterTest`
- 真实上传：
  - `静安约会计划.pdf` → READY，9 chunks；
  - `docling-test.docx` → READY，1 chunk；
  - DOCX 文档详情接口返回 HTTP 200（本地解析不可用时使用 chunks）。
- `mvn -o -DskipTests package` 成功；后端重启后 `/api/health` 返回 `ok`；`git diff --check` 通过。
- Docling Serve 容器仍运行：`yu-ai-docling-serve`，`127.0.0.1:5001`，模型缓存约 507MB。

## 遗留事项

- 当前使用同步 chunk API；大文件可能触发 Docling 120s 同步等待上限，后续应切换 async + 轮询。
- 中文扫描 PDF 的 OCR 质量尚未用真实业务资料评测。
- 文档详情页的 `sourceContent` 仍是本地解析结果；Docling 原始 Markdown 尚未持久化。
- x86_64 部署需要调整 `build-runtime.sh` 的 platform/Python ABI/torch wheel。
- 本轮改动尚未提交。

## 环境注意事项

- `src/main/resources/application-local.yml` 未读取、编辑、复制或加入 Git。
- 后端由 launchctl 管理；已重新打包并重启。
- Docling Serve 使用 Docker 卷 `docling-cache` 持久化模型。
