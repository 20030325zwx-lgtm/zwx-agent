# zwx-browser-mcp-server

基于 Playwright + SSE 的 MCP 服务，提供 `screenshot_page`：

- 在无头 Chromium 中打开公网网页并截图；
- 支持整页截图、视口尺寸和等待时间；
- 截图保存在本地 `screenshots/`，通过短时 URL 访问；
- 默认拒绝内网和回环地址，避免 SSRF。

## 本地运行

```bash
npm install
CHROME_EXECUTABLE_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  npm start
```

默认端口 `8129`，SSE 地址 `http://127.0.0.1:8129/sse`，健康检查 `GET /health`。

如果使用 Playwright 自带浏览器，去掉 `CHROME_EXECUTABLE_PATH` 并先执行 `npx playwright install chromium`。

## Docker

```bash
cp .env.example .env
docker compose up -d --build
```

主应用在 `/mcp-settings` 添加：

```text
名称：网页截图
地址：http://127.0.0.1:8129
```

## 工具参数

```json
{
  "url": "https://example.com",
  "full_page": true,
  "width": 1440,
  "height": 900,
  "wait_ms": 1000
}
```

返回：

```json
{
  "url": "http://127.0.0.1:8129/screenshots/xxx.png",
  "markdown": "![screenshot](http://127.0.0.1:8129/screenshots/xxx.png)",
  "filename": "xxx.png",
  "width": 1440,
  "height": 900,
  "full_page": true,
  "bytes": 123456
}
```

生产环境请设置 `PUBLIC_BASE_URL` 为浏览器可访问的地址，并限制 `screenshots/` 的访问范围。
