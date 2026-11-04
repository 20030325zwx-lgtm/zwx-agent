# 会话摘要：文件生成 P2（docx/pptx/PDF 引擎 + backend 生成工具）（2026-11-04）

## 改动内容

P2 把 artifact-mcp 从骨架做成可用的排版引擎，并让 backend 暴露三个生成工具。**架构修正**：生成不做成 MCP 工具，而是「backend 工具（落盘）+ 引擎服务（排版）」，原因见 README（MCP 工具拿不到租户/工作区上下文）。

1. **artifact-mcp 引擎**（`services/artifact-mcp`）
   - `DocumentRenderer`：Markdown-lite（#/##/###、-/*、1.、段落、`**粗体**`、管道表格）→ docx；`fillTemplate` 支持 `{{key}}` 占位符替换。
   - `PresentationRenderer`：结构化 slides → 16:9 pptx（显式文本框，不依赖母版；封面 + 要点页）。
   - `PdfRenderer`：Markdown-lite → PDF（iText，CJK 字体可配置/自动发现）。
   - `ConversionService`：headless LibreOffice 转换 docx/pptx/xlsx → PDF，临时目录用后即删。
   - `RenderController`：`/internal/render/{docx,pptx,pdf,convert,capabilities}`，内网可选令牌 `X-Aster-Artifact-Token`，入参限额（Markdown 2M 字符 / 文件 20MB）。
   - Dockerfile 改为 Debian + LibreOffice + Noto CJK + curl（健康检查）；镜像约 800MB+。
   - 测试 5/5：docx 结构、模板替换、pptx 页数与文本、PDF 头、真实 LibreOffice 转换。
2. **backend 生成工具**
   - `ArtifactClient`（JDK HttpClient，超时/大小上限/可选令牌）。
   - `ArtifactDocumentTool`：`createDocument`（docx）、`createPresentation`（pptx，slides JSON 校验 ≤60 页）、`createPdfFromMarkdown`（pdf）；落盘走 `ToolSandbox.resolveWithin`，返回 `{file, bytes}`。
   - `ToolFactory` 仅在 `app.artifact.base-url` 非空时注册（避免模型调到必然失败的工具）；`ToolRiskCatalog` 三个工具登记为 `WRITE`。
   - 测试 4/4：引擎调用与落盘、slides 校验、引擎 500 可读错误、未配置时拒绝写入。
3. **部署**
   - compose 新增 `artifact-mcp`（profile `artifacts`，mem_limit 1G，健康检查 `/internal/render/capabilities`）；backend 增加 `APP_ARTIFACT_BASE_URL` / `APP_ARTIFACT_TOKEN` 环境变量。
   - `package.sh` 支持 `WITH_ARTIFACT_MCP=true` 构建并打包该镜像（默认关闭，避免每次发布多打 800MB）。
   - `application.yml` 新增 `app.artifact.*`（base-url/token/timeout/max-bytes）。

## 验证结果

- artifact-mcp `mvn test` 5/5；backend 相关测试 4+2+4 全通过；backend `mvn -DskipTests package` 通过。
- 本地实跑引擎（8130）：`capabilities` 显示 soffice/字体可用；渲染 docx 2688B（有效 OOXML）、pptx 27KB、docx→pdf 28KB（有效 PDF 1.7）。
- 未做「backend 重启后经 agent 端到端调用」验证：本地 backend 需带 `APP_ARTIFACT_BASE_URL` 重启才注册工具（为不打断并行会话未重启）；工具与引擎两侧均已单测/直连验证。

## 遗留事项

- P3：模板上传与管理（当前模板可通过工作区文件 + `templateBase64` 传入，缺 UI 入口与模板库）；把 backend 的 `generatePDF/generateSpreadsheet` 并入引擎后统一收口。
- 部署时需注意：`docker compose --profile artifacts up -d artifact-mcp` + `.env` 设 `APP_ARTIFACT_BASE_URL=http://artifact-mcp:8130`，否则工具不会出现。
- 生产建议设 `ARTIFACT_INTERNAL_TOKEN` 并把 `APP_ARTIFACT_TOKEN` 指向同值，避免内网接口裸奔。

## 环境注意事项

- 本机有 LibreOffice 25.2 与 `/Library/Fonts/Arial Unicode.ttf`，转换/中文 PDF 可直接本地验证；容器用 Noto CJK。
- poi-ooxml 从空演示文稿创建时母版可能为空：pptx 排版不要依赖母版占位符，直接用文本框。
- iText 9 的 `Paragraph` 没有 `setBold(boolean)`；加粗需要单独字体或改用字号/颜色区分。
