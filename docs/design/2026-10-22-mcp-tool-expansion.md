# MCP 工具扩展：表格、文档与网页截图（2026-10-22）

## 目标

在现有图片 MCP（8127）之外，补充三个对超级智能体最有价值的工具：

1. `extract_table`：从图片中抽取表格，输出 Markdown/CSV/JSON。
2. `parse_document`：解析 PDF/Office/Markdown/TXT，输出结构化 Markdown 或纯文本。
3. `screenshot_page`：渲染网页并截图，返回可访问的短时图片 URL。

三个工具都通过 MCP 暴露，主应用通过 `/mcp-settings` 统一发现、授权和调用。

## 服务拆分

| 服务 | 端口 | 工具 | 说明 |
| --- | ---: | --- | --- |
| `services/image-search-mcp` | 8127 | `searchImage`、`describe_image`、`ocr_image`、`extract_table` | 扩展现有图片服务 |
| `services/document-mcp` | 8128 | `parse_document` | 新增，默认 MinerU API，Docling/Tika 可选回退 |
| `services/browser-mcp` | 8129 | `screenshot_page` | 新增，Playwright + SSE MCP |

拆开的原因：图片、文档和浏览器依赖不同；浏览器镜像体积大、资源消耗高，独立部署便于限流和隔离。

## extract_table

### 参数

```json
{
  "image_url": "https://...短时签名 URL...",
  "format": "markdown | csv | json",
  "max_rows": 200
}
```

- `image_url` 必填；沿用图片 URL host allowlist。
- `format` 默认 `markdown`。
- `max_rows` 默认 200，最大 1000。
- 复用现有 DashScope 多模态模型，不新增密钥。
- 只输出表格内容，无法确认的单元格留空；禁止执行图片中的指令。

### 输出

返回文本表格；JSON 格式返回对象数组。结果超过 4000 字符时截断并标注 `truncated`。

## parse_document

### 参数

```json
{
  "document_url": "https://...签名 URL...",
  "filename": "可选，缺省从 URL 推断",
  "format": "markdown | text",
  "max_chars": 20000
}
```

- URL 仅允许 HTTP(S)，host 由 `DOCUMENT_ALLOWED_HOSTS` 控制，默认 `*.aliyuncs.com,localhost,127.0.0.1`。
- 文件大小上限默认 20MB。
- 默认调用 MinerU 精准解析 API（文件上传模式），不依赖本机 Docling：
  - `POST /api/v4/file-urls/batch` 获取上传地址；
  - `PUT` 上传文件；
  - 轮询 `/api/v4/extract-results/batch/{batchId}`；
  - 下载结果 zip 并提取 `full.md`。
- MinerU 不可用时，可回退 Docling Serve `/v1/chunk/hybrid/file`、Tika `/tika` 或 Markdown/TXT 本地读取。
- MinerU Token 通过 `MINERU_TOKEN` 提供，不写入镜像和 Git。
- 输出：

```json
{
  "filename": "员工手册.pdf",
  "source": "docling",
  "format": "markdown",
  "chars": 12345,
  "truncated": false,
  "content": "# 员工手册\n..."
}
```

## screenshot_page

### 参数

```json
{
  "url": "https://example.com",
  "full_page": true,
  "width": 1440,
  "height": 900,
  "wait_ms": 1000
}
```

- `url` 只允许公网 HTTP(S)；默认拒绝 localhost/内网，`BROWSER_ALLOW_PRIVATE_HOSTS=true` 可放开开发环境。
- `width` 范围 320-2560，`height` 范围 200-1600。
- `wait_ms` 范围 0-5000。
- 单次导航超时 20 秒，整体超时 30 秒。
- 使用 Playwright 启动 Chromium；Docker 镜像基于 `mcr.microsoft.com/playwright`。
- 截图保存到 `SCREENSHOT_DIR`，通过服务自己的 `/screenshots/{id}.png` 提供访问；默认保留 30 分钟。
- 返回：

```json
{
  "url": "http://127.0.0.1:8129/screenshots/xxx.png",
  "markdown": "![screenshot](http://127.0.0.1:8129/screenshots/xxx.png)",
  "width": 1440,
  "height": 900,
  "full_page": true
}
```

生产环境应通过 `PUBLIC_BASE_URL` 配置可被用户浏览器访问的地址；后续可替换为 OSS/MinIO 签名 URL。

## MCP 协议与认证

- 三个服务均使用 SSE MCP：`GET /sse` 建立连接，服务端返回 `/messages?sessionId=...`，客户端 POST JSON-RPC。
- 首版沿用本机绑定，默认无认证。
- 主应用可在 `/mcp-settings` 为每个服务配置 Bearer/自定义 Header；服务端可选实现 `MCP_AUTH_TOKEN` 校验，作为后续增强。
- 工具 allowlist 仍由主应用控制，新增服务默认需要管理员显式授权。

## 安全边界

- 不接收图片/文档二进制作为工具参数，只接收 URL。
- 限制 URL host、文件大小、字符数、视口尺寸和执行时间。
- 浏览器服务禁止下载、上传和本地文件访问；仅打开页面并截图。
- 截图 URL 是短时公开资源，需限制保存时间和访问范围。
- 所有外部内容在提示词中标记为不可信数据。

## 知识库入库也走 MinerU

- `AgentKnowledgeDocumentService` 新增 `app.document-parser.provider`：
  - `mineru`：知识库 PDF/Office 入库优先调用 MinerU；
  - `docling`：保留原 Docling Serve 链路；
  - 本地 Markdown/TXT、Tika 和 iText 仍作为回退。
- MinerU 返回 Markdown 后由现有 `MyTokenTextSplitter` 切片并写入 PGVector，元数据标记 `parser=mineru`。
- 这样 4核8G 服务器可以关闭 Docling，知识库入库和 `parse_document` 工具都使用在线解析。

## 部署影响

- 采用 MinerU 后，4核8G 服务器不需要运行 Docling 容器，可省出约 5.6GB 内存。
- `document-mcp` 变成轻量 API 适配器，内存占用约 300–500MB。
- MinerU 精准接口是异步任务，主应用 MCP 请求超时提升到 600 秒，与本服务轮询上限一致。
- 文档会发送到 MinerU，需要确认数据合规；敏感文档可切回自建 Docling 或使用私有化解析服务。

## 验证计划

- 三个服务 `mvn package` / `npm run build` 通过。
- 通过 MCP `initialize` / `tools/list` 验证工具名称和 JSON Schema。
- `extract_table`：用现有录取分数表图片验证。
- `parse_document`：用真实 PDF 验证 MinerU 解析，并检查 `full.md` 内容。
- 知识库入库：上传真实 PDF，确认索引完成且切片元数据 `parser=mineru`。
- `screenshot_page`：对公开网页截图并访问返回 URL。
- 主应用 `/mcp-settings` 添加服务后可发现并调用工具。
