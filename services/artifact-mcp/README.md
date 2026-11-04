# artifact-mcp（产物排版引擎）

文档/演示/PDF 的**无状态排版引擎**，由 backend 的生成工具内网调用。当前能力：

| 能力 | 接口 | 说明 |
|---|---|---|
| Word 文档 | `POST /internal/render/docx` | Markdown-lite → docx（标题/要点/编号/表格/行内粗体） |
| 模板填充 | 同上，传 `templateBase64` + `values` | 替换模板里的 `{{key}}` 占位符 |
| 演示文稿 | `POST /internal/render/pptx` | 结构化 slides → 16:9 pptx（封面 + 要点页） |
| PDF | `POST /internal/render/pdf` | Markdown-lite → PDF（CJK 字体） |
| 格式转换 | `POST /internal/render/convert` | docx/pptx/xlsx → PDF（headless LibreOffice） |
| 能力探测 | `GET /internal/render/capabilities` | 返回已装能力、LibreOffice / 字体是否可用 |

## 为什么是"引擎 + backend 工具"，而不是直接把生成做成 MCP 工具

MCP 工具的参数只来自模型，**拿不到「租户 / 会话 / 运行工作区」上下文**。而交付物必须落到当前会话工作区，
才能复用既有的 `workspace/files` 列表、签名/鉴权下载与前端产物卡片。如果硬做成 MCP 工具，只能：

1. 让模型自己传输出路径（不可控、易写错、有越权风险），或
2. 通过 MCP 回传二进制（大文件拖垮协议、还要再落盘一次）。

因此分层：

- **backend 工具**（`createDocument` / `createPresentation` / `createPdfFromMarkdown`，WRITE 级）
  负责参数校验、调用引擎、把字节写进会话工作区、返回 `{file, bytes}`；
- **本服务**只做重依赖排版（POI / iText / LibreOffice / CJK 字体），不持有任何业务状态。

MCP 端点（`artifactServiceInfo`）保留给 MCP 客户端做能力探测，不承担生成落盘。

## 本地运行

```bash
cd services/artifact-mcp
mvn -q spring-boot:run            # http://127.0.0.1:8130
curl -fsS http://127.0.0.1:8130/internal/render/capabilities
```

让本地 backend 启用生成工具：启动时加环境变量
`APP_ARTIFACT_BASE_URL=http://127.0.0.1:8130`（未设置则不注册相关工具）。

## 部署

```bash
docker build -t aster-artifact-mcp:dev services/artifact-mcp
# 或打包时：VERSION=x.y.z WITH_ARTIFACT_MCP=true sh deploy/scripts/package.sh
```

compose 里是 `profiles: ["artifacts"]` 的可选服务：

```bash
docker compose --profile artifacts up -d artifact-mcp
# .env: APP_ARTIFACT_BASE_URL=http://artifact-mcp:8130   （backend 侧启用）
# 可选：ARTIFACT_INTERNAL_TOKEN=<随机串>（同时用于 backend 的 APP_ARTIFACT_TOKEN）
```

镜像内含 `libreoffice-writer/calc/impress` 与 `fonts-noto-cjk`，约 800MB+；只做 docx/pptx/pdf 渲染
不需要转换时，可以删掉 Dockerfile 里的 libreoffice-* 以减小体积。

## 限额与安全

- 入参：Markdown ≤ 2,000,000 字符；模板/待转换文件 ≤ 20MB；转换超时默认 120s。
- 输出：backend 侧 `app.artifact.max-bytes`（默认 50MB）。
- 认证：设置 `ARTIFACT_INTERNAL_TOKEN` 后，`/internal/**` 必须带 `X-Aster-Artifact-Token`。
- 路径：backend 工具用 `ToolSandbox.resolveWithin` 限制在工作区内；本服务转换只写临时目录，用后即删。
- 字体：容器内 `/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc`，本地回退 `/Library/Fonts/Arial Unicode.ttf`。
