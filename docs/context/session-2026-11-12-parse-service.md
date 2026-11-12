# 会话摘要：统一解析服务（Python sidecar）落地（2026-11-12）

## 需求

用户决定"先搞统一解析服务"。目标：把三套互不兼容的解析实现（MinerU 远程 / Docling Serve
chunk-only / iText 本地）收敛为一个 Python 服务，输出 MinerU 等价结果包，Java 侧复用现有
块解析/落库/切片/图纸增强管线。范围按推荐默认：生产 provider 保持 mineru（开关就绪）、
不进桌面端、不进离线安装包。

## 改动

Python 服务（新增 `services/parse-service/`）：
- FastAPI：`GET /health`（不依赖模型）、`POST /parse`（multipart，返回 zip）。
- `app/content_list.py`：DoclingDocument → MinerU 兼容 content_list（鸭子类型、不 import docling，
  可脱离模型单测）；bbox 左下→左上、heading 层级、表格 HTML、图片 PNG 导出、caption 引用；
  bbox 缺失时省略字段（避免 Java 侧写出 "null" jsonb）。
- `app/converter.py`：Docling 惰性加载（OCR/表格结构/图片导出），临时文件转换，zip 打包。
- `app/main.py`：大小限制、并发信号量（默认 2）。
- `Dockerfile`（python:3.11-slim + tesseract 中文 + docling）、本地 compose、README。

Java 适配：
- 新增 `PythonParseClient`（multipart → zip → `MineruDocumentParser.extractResult`）与
  `PythonParseException`；`MineruDocumentParser` 增加带限额的静态 `extractResult(byte[], long)`。
- `AgentKnowledgeDocumentService`：抽出 provider 无关的 `structuredChunks()`；
  新增 `pythonChunks()` 与 `provider=python` 分支；降级链 python → docling → local；
  `parser=python` 视为完整质量（不标 parseQuality=degraded）。
- 新增 `ParseServiceHealthIndicator`（失败 UNKNOWN，不拉低整体）。
- `application.yml` 新增 `app.document-parser.python.*`（enabled=false / 8131 / 300s / 100MB）。

编排与发布：
- 主 compose 新增 `parse-service`（`profiles: ["parse"]`、expose 8131、mem_limit 3g、
  healthcheck、parse-cache 卷）；backend 增加 python 解析环境变量；`.env.example` 补键与启用步骤。
- `deploy/scripts/deploy-to-server.sh`（本地脚本，不提交）：新增 parse-service 变更检测、
  构建上下文 tar 上传、**服务器端 docker build**（避免上行 2.5GB）、compose 同步（带备份）、
  `docker compose --profile parse up -d parse-service`、容器健康轮询；兼容"只发解析服务"
  （无 backend/frontend 变更时不再直接 die）。

## 验证

- Python：content_list 契约单测 7/7 绿（bbox 转换、缺省字段、表格/图片/公式/标题、页眉页脚跳过）。
- Java：`PythonParseClientTest` 4/4 绿（zip→blocks、enabled 关闭、HTTP 500、连接失败）；
  既有 Mineru/Chunker 测试全绿。
- 全量 655 例：失败 6 个均为既有基线（MediaJob* 创作抖动 + DashScope 欠费），knowledge 零涟漪。
- `bash -n` 脚本语法、`py_compile`、`docker compose config` 均通过。

## 遗留

- 真实 PDF 冒烟（`PARSE_SMOKE_PDF`）未在本机执行：需装 docling 模型；上线后首次入库需人工
  核对 bbox/图纸分组/引用定位。
- 生产 provider 未切换（`.env` 一行 `APP_DOCUMENT_PARSER_PROVIDER=python`，验证质量后再切）。
- 桌面端不打包 Python；离线安装包不含 parse-service（profile 隔离）。
- document-mcp 的解析路径（`DOCLING_ENABLED` 硬编码 false）未收口。
- 后续引擎（PyMuPDF 快速文本、本地 MinerU）未实现，服务已留 `engine` 参数。

## 部署（已完成，0.7.38）

- parse-service 镜像在**服务器端构建**（2.83GB，含 CPU torch/docling），发布脚本已支持
  parse-service 变更检测、构建上下文上传、compose 同步（带备份）、profile 启动、容器健康轮询。
- 服务器网络实测：**Docker Hub 不可达**、Debian 官方源极慢、PyPI/PyTorch/阿里云/清华镜像可达。
  因此：基础镜像走 `docker.m.daocloud.io`；apt 源默认切阿里云（`APT_MIRROR` 可覆盖）；
  torch/torchvision 用阿里云 `+cpu` wheel 直链（amd64）/官方 CPU 索引（arm64）；
  pip 依赖用 uv 解析（pip 在 transformers 多版本间回溯极慢）。
- 发布脚本修复：远端命令幂等（不再在构建命令里删上传包，重试可重入）、上传后 md5 校验+重试、
  SSH 长连接保活、独立构建脚本 `deploy/scripts/parse-service-build.sh`（镜像存在即跳过）。

## 验证（线上）

- parse-service 容器 healthy；backend 0.7.38 healthy；公网 200。
- 后端 actuator 已注册 `parseService` 组件（未启用 → UNKNOWN，符合预期）。
- 容器内 DOCX 冒烟：8.3s 返回 741B zip，5 个块（4 text + 1 table，表格 HTML 正常），
  DOCX 无 provenance 时 bbox 按契约省略（未写出 "null"）。
- PDF 冒烟：首次 181.2s（含 Docling layout 模型下载，之后模型走 parse-cache 卷），
  再次 15.4s；content_list 2 个 text 块，bbox 正确（top < bottom，如 `[72, 80.51, 370.8, 123.31]`）。
- 冒烟发现并修复一个真实 bug：bbox 上下边写反（左下原点转换公式错误），已补断言并发布 0.7.39。

## 最终线上状态（0.7.39）

- parse-service：healthy，镜像 2.83GB，`profiles: ["parse"]` + `COMPOSE_PROFILES=parse` 已写入服务器 .env。
- backend：0.7.38 healthy；`parseService` 健康组件为 UNKNOWN（`APP_DOCUMENT_PARSER_PYTHON_ENABLED=false`）。
- **生产解析仍未切换**：`APP_DOCUMENT_PARSER_PROVIDER` 保持 `mineru`；切换只需服务器 .env 一行。
