# 会话摘要：MCP 工具扩展（2026-10-22）

## MinerU 接入（追加）

- `document-mcp` 默认 provider 改为 MinerU 精准解析 API，使用文件上传模式：
  - `POST /api/v4/file-urls/batch` 获取上传地址；
  - `PUT` 上传文件；
  - 轮询 `/api/v4/extract-results/batch/{batchId}`；
  - 下载结果 zip 并提取 `full.md`。
- Token 维护在 `services/document-mcp/.env`（已被 Git 忽略），不提交、不回显。
- `DOCLING_ENABLED` 默认改为 `false`，Docling/Tika 仅作可选回退；4核8G 服务器不再需要 Docling 容器。
- 主应用 MCP 请求超时从 90 秒提升为可配置 `app.mcp.request-timeout-seconds`，默认 600 秒。
- 主应用知识库入库也接入 MinerU：`app.document-parser.provider=mineru`，Docling/Tika/本地作为回退。
- 修复知识库索引线程池 Bean 缺失（`knowledgeIndexExecutor`）。
- 增加 `McpToolRegistry`：MCP 工具被发现并授权后，ToolGuard 不再把 `zwx_agent_*` 工具当作未登记工具拦截。

## 改动内容

- 新增设计文档 `docs/design/2026-10-22-mcp-tool-expansion.md` 并加入索引。
- 图片 MCP（`services/image-search-mcp`）新增 `extract_table`：
  - 参数：`image_url`、`format`（markdown/csv/json）、`max_rows`（默认 200，最大 1000）；
  - 复用 DashScope 多模态模型；
  - 明确表格只作为数据，不执行图片中的指令。
- 新增 `services/document-mcp`（端口 8128）：
  - 工具 `parse_document(document_url, filename, format, max_chars)`；
  - 优先 Docling Serve，失败回退 Tika 或 Markdown/TXT；
  - URL host allowlist、20MB 下载上限、输出字符截断；
  - 提供 SSE/stdio profile、Dockerfile、compose、README 和 Spring 上下文测试。
- 新增 `services/browser-mcp`（端口 8129）：
  - Node.js + Playwright + MCP SSE Server；
  - 工具 `screenshot_page(url, full_page, width, height, wait_ms)`；
  - 默认拒绝私网地址，截图落本地目录并提供短时 URL；
  - 提供 Dockerfile、compose、README 和 `.gitignore`。

## 验证结果

- 图片 MCP `mvn -DskipTests package` 成功；Docker 镜像已重建，`tools/list` 包含：
  - `searchImage`、`ocr_image`、`extract_table`、`describe_image`。
- 文档 MCP `mvn test` 成功；本地 8128 通过 MCP 客户端调用 `parse_document`：
  - Markdown 文本回退解析成功；
  - 使用 `静安约会计划.pdf` 验证 Docling 路径成功，返回 `source=docling` 的结构化 Markdown。
- 浏览器 MCP 本地启动成功；通过 MCP 客户端调用 `screenshot_page` 截图 `https://example.com`，返回 PNG URL，`GET` 截图返回 200。
- 三个新 MCP 服务的 `tools/list` 均已通过真实 SSE 握手验证。
- MinerU 真实解析成功：通过 document-mcp 上传 PDF，返回 `source=mineru` 的 Markdown。
- 主应用端到端成功：超级智能体调用 `zwx_agent_parse_document`，MinerU 解析 PDF 后返回内容摘要。
- 知识库入库验证成功：上传 PDF 后状态 READY，3 个切片，元数据 `parser=mineru`。
- 主应用 `mvn test`：198 个测试，0 失败，0 错误，1 跳过；后端 `/api/health` 正常。

## 遗留事项

- `extract_table` 的模型真实表格抽取因 DashScope 不接受 localhost 图片 URL，尚未用公开表格图做端到端验证；代码复用已验证的 `describe_image` 视觉链路。
- 文档 MCP 的 Tika 回退路径尚未实测（未配置 Tika Server）。
- 主题服务图片 URL 是本地短时 URL，生产需设置 `PUBLIC_BASE_URL` 或改为 OSS/MinIO 签名 URL。
- 本地已把“文档解析”注册到主应用数据库，指向 `http://127.0.0.1:8128`；`document-mcp` 当前在本机运行。
- 网页截图 MCP 尚未注册到主应用数据库；需要时在 `/mcp-settings` 添加 `http://127.0.0.1:8129`。
- 本轮改动尚未提交。
