# aster-document-mcp-server

独立的 SSE MCP 服务，提供 `parse_document`：

- 输入短时公开文档 URL、可选文件名、输出格式和最大字符数；
- 默认调用 MinerU 精准解析 API（文件上传模式，避免签名 URL 过期）；
- MinerU 不可用时可选回退 Docling Serve、Tika 或 Markdown/TXT；
- 返回 Markdown 或纯文本，不接收文档二进制作为工具参数。

## 本地运行

```bash
mvn -DskipTests package
MINERU_TOKEN=<你的 MinerU Token> \
  java -jar target/aster-document-mcp-server-0.0.1-SNAPSHOT.jar
```

默认端口 `8128`，SSE 地址 `http://127.0.0.1:8128/sse`，健康检查 `GET /health`。

## Docker

```bash
cp .env.example .env
docker compose up -d --build
```

服务只绑定 `127.0.0.1:8128`。Token 放在 `services/document-mcp/.env`，不要提交到 Git。主应用在 `/mcp-settings` 添加：

```text
名称：文档解析
地址：http://127.0.0.1:8128
```

## 工具参数

```json
{
  "document_url": "https://...短时签名 URL...",
  "filename": "员工手册.pdf",
  "format": "markdown",
  "max_chars": 20000
}
```
