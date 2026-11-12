# 统一解析服务：Python sidecar 输出 MinerU 兼容结果包（2026-11-12）

## 0. 结论先行

知识库入库的解析链路从「MinerU 远程 / Docling Serve chunk-only / iText 本地」三套不兼容实现，
收敛为「一个 Python 解析服务输出 MinerU 等价结果包」，Java 侧复用现有的
`MineruBlockParser → persistBlocks → StructuredKnowledgeChunker → figureSupport` 全链路。

选择这个切法而不是重写编排：解析是 Python 生态的主场（Docling/PyMuPDF/OCR/版面分析），
而编排、持久化、权限、审计是 Java 侧的护城河——两者通过结果包契约解耦，互不侵入。

范围约定：
- **服务端默认 provider 保持 `mineru`**；统一解析服务以 `profiles: ["parse"]` 就绪，一行配置切换。
- **不进 macOS DMG、不进离线安装包**（profile 隔离，桌面继续 iText）。

## 1. 架构

```
┌──────────────────────────── Java（知识库入库，异步 knowledgeIndexExecutor）────────────────────────────┐
│ indexDocument                                                                                         │
│   provider=mineru  → MineruDocumentParser（远程 SaaS）──┐                                              │
│   provider=python  → PythonParseClient（HTTP multipart）──┼→ MineruParseResult(markdown, files)          │
│   （失败/未配置）    → DoclingServeChunkingService（chunk-only）→ localChunks（iText/Tika）              │
│                                                          │                                            │
│   structuredChunks(): MineruBlockParser → persistMineruFiles/persistBlocks → FigureEnrichment →        │
│                        StructuredKnowledgeChunker（text/table_row/figure_description/figure_question）   │
└───────────────────────────────────────────────────────────────────────────────────────────────────────┘
                                                          ▲ zip：full.md + content_list.json + images/*
┌──────────────────────────── Python sidecar（services/parse-service）────────────────────────────────────┐
│ FastAPI: GET /health（不依赖模型）  POST /parse（multipart: file, engine=docling）                      │
│ converter.py：Docling DocumentConverter（OCR/表格结构/图片导出，模型惰性加载）                          │
│ content_list.py：DoclingDocument → MinerU 兼容 JSON（bbox 左下→左上，caption 引用，heading 层级）        │
└───────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. 契约

### 2.1 HTTP

| 端点 | 请求 | 响应 |
|---|---|---|
| `GET /health` | - | 200 `{"status":"ok"}`（启动即可用，不触发模型加载） |
| `POST /parse` | multipart：`file`（必填）、`engine`（默认 `docling`） | 200 `application/zip`；415/422/500 JSON 错误 |

限制：单文件默认 50MB（`PARSE_MAX_BYTES`），并发默认 2（`PARSE_MAX_CONCURRENT`）。

### 2.2 zip 内容（与 MinerU 结果包同名同构）

```
full.md                  # 兜底 Markdown
content_list.json        # 结构化块数组
images/p{page}_{n}.png   # 图纸/图片裁剪
```

`content_list.json` 字段（`MineruBlockParser` 消费）：

| type | 字段 |
|---|---|
| `text` | `text`、`page_idx`（0-based）、`bbox`、标题带 `text_level>0` |
| `table` | `table_body`（HTML）、`table_caption[]`、`table_footnote[]` |
| `image` | `img_path`、`img_caption[]`、`image_footnote[]` |
| `equation` | `text`（LaTeX） |

**bbox 缺失时省略字段**：Java 侧对显式 null 会 `toString()` 成字符串 `"null"`，写 jsonb 会失败。

### 2.3 复用点（Java 侧零重复实现）

- `MineruDocumentParser.extractResult(byte[], maxZipBytes)`：新增的静态重载，解包 + 限额，供外部来源复用。
- `MineruBlockParser`：与来源无关，只要结果包含 `content_list.json`。
- `persistMineruFiles/persistBlocks/figureSupport`：原图落 OSS 与图纸增强不变。
- `StructuredKnowledgeChunker`：四类 chunk 与 metadata（sectionPath/bbox/rowStart/figureImages）不变。

## 3. Java 集成

| 项 | 位置 | 说明 |
|---|---|---|
| 客户端 | `knowledge/document/PythonParseClient.java` | multipart POST → zip → `extractResult`；异常 `PythonParseException` |
| provider 分支 | `AgentKnowledgeDocumentService.indexDocument` | `provider=python` 尝试 → 失败进 Docling → local；`useDocling` 条件纳入 python |
| 切片管线 | `structuredChunks(...)` | 从原 `mineruChunks` 抽出，provider 无关 |
| 质量标记 | `toScopedChunk` | `parser ∈ {mineru, python}` 视为完整质量；其余标 `parseQuality=degraded` |
| 健康 | `platform/health/ParseServiceHealthIndicator` | 失败报 UNKNOWN（不影响整体 UP） |
| 配置 | `application.yml` → `app.document-parser.python.*` | enabled=false / base-url=127.0.0.1:8131 / timeout=300s / max-zip=100MB |

降级链（provider=python）：**Python → Docling（若 enabled）→ local**，与 mineru 分支一致。

## 4. 部署

- compose：`parse-service` 服务，`profiles: ["parse"]`、`expose: 8131`（不发布公网）、
  `mem_limit: 3g`、healthcheck、命名卷 `parse-cache:/root/.cache/huggingface`。
- backend 环境变量：`APP_DOCUMENT_PARSER_PYTHON_ENABLED`（默认 false）与 `APP_DOCUMENT_PARSER_PYTHON_BASE_URL`。
- 发布脚本：`services/parse-service/` 变更触发——打包构建上下文 → 上传 → **服务器端 `docker build`**
  （避免上行 ~2.5GB 镜像）→ 同步 compose（带备份）→ `docker compose --profile parse up -d parse-service`
  → 轮询容器 `/health`。compose 单独变更也会同步。
- 启用步骤（服务器 `.env`）：
  ```
  COMPOSE_PROFILES=parse
  APP_DOCUMENT_PARSER_PYTHON_ENABLED=true
  # 质量验证后再切换入库解析：
  APP_DOCUMENT_PARSER_PROVIDER=python
  ```
- 离线安装包（`package.sh`）不含 parse-service；profile 隔离保证 `docker compose up -d` 不受影响。

## 5. 已知风险

1. **bbox 坐标系**：Docling 为左下原点（`t` 是上边、`b` 是下边），转换为
   `top' = page_height - t`、`bottom' = page_height - b`，结果必须 top' < bottom'。
   假对象单测 + 线上 PDF 冒烟已验证（曾把上下边写反，冒烟发现并修复）。
2. **首次解析下载模型**：Docling 模型权重下载到 `HF_HOME`，需联网；`parse-cache` 卷复用。
3. **服务器构建耗时**：torch CPU 体积大，首次 `docker build` 可能 10-20 分钟；后续层缓存。
   服务器无法访问 Docker Hub（实测超时），发布脚本通过 build-arg 走 daoCloud 基础镜像与
   清华 PyPI 源；torch/torchvision 固定 `+cpu` 本地版本（PyPI 默认 wheel 自带 CUDA，体积翻倍）。
4. **并发内存**：Docling 单文档峰值数百 MB；`mem_limit: 3g` + 并发 2 为稳态假设，压测后调整。
5. **表格结构**：Docling `export_to_html` 覆盖多数表格；无结构时降级为文本（与 MinerU 行为一致）。

## 6. 边界（本次不做）

- 不改 macOS 桌面端（无 Python 运行时，`application-desktop.yml` 保持 docling 关闭）。
- 不改离线安装包与 `document-mcp` 的解析路径。
- 不实现 PyMuPDF / 本地 MinerU 引擎（服务内已留 `engine` 参数）。
- 不切换生产默认 provider。
