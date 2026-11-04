# artifact-mcp（产物生成 MCP 服务，P2 骨架）

交付物生成服务。当前是**可运行的骨架**：只提供 `artifactServiceInfo` 一个工具，用来回答「现在能生成什么 / 计划生成什么 / 产物契约」。

## 为什么做成独立 MCP 服务

- docx / pptx / 格式转换（docx→pdf）依赖重库（POI 高级特性、LibreOffice、中文字体包），塞进 backend 会显著膨胀镜像、拖慢启动；
- 文档生成是 CPU/内存尖峰，独立容器可 `mem_limit` + 超时 + 崩溃隔离；
- 加格式 / 升级库不需要发 backend 版本；
- 复用现有 MCP 注册（`/mcp-settings`、`src/main/resources/mcp-servers.json`）、确认链路与工具发现。

## 产物契约（P2 落地时必须遵守）

- 共享卷 `artifacts:/artifacts` 同时挂到 `backend` 与 `artifact-mcp`；
- 工具**只返回相对路径 + mime + 字节数（+ 页数/行数）**，不通过 MCP 传二进制；
- 单文件 ≤ 50MB，单次生成 ≤ 120s；
- 进程内路径守卫：禁止 `..`、绝对路径、符号链接逃逸；
- 同一 run 重复生成同名文件为覆盖（幂等），不追加。

## P1 现状（本仓库）

P1 的交付物生成仍由 backend 内置工具承担，无需本服务：

| 工具 | 位置 | 格式 |
|---|---|---|
| `writeFile` | backend | md / txt / csv / json / html |
| `generatePDF` | backend | pdf |
| `generateSpreadsheet` | backend | xlsx（POI） |

前端 `DeliverableCard.vue` 会把本轮窗口内生成的文件以「产物卡片」展示在对话流里，走既有签名下载。

## P2 计划

1. 工具：`createDocument`（docx，模板驱动）、`createPresentation`（pptx）、`createPdfFromMarkdown`（长文档/目录/页眉页脚）、`convertFile`（docx→pdf 等）；
2. 把 backend 的 `generateSpreadsheet` / `generatePDF` 迁移过来，backend 只保留 `writeFile` 文本能力；
3. 部署：镜像 `aster-artifact-mcp:${IMAGE_TAG}`，compose 增加服务（profile `artifacts`），`mcp-servers.json` 注册 `http://artifact-mcp:8130`；
4. 字体：镜像内随附 Noto Sans CJK / 思源，避免服务器缺字体导致中文方块。

## 本地运行

```bash
cd services/artifact-mcp
mvn -q spring-boot:run            # SSE，http://127.0.0.1:8130
```

## 构建镜像

```bash
docker build -t aster-artifact-mcp:dev services/artifact-mcp
```
