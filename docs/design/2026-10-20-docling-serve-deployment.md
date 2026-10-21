# Docling Serve 本地部署（OrbStack）

> 状态：已部署（2026-10-20）。服务地址：`http://127.0.0.1:5001`；仅绑定本机。
> 目标：把 PDF/Word 等文档的解析与切片从当前 Spring Boot 服务中拆出去，作为独立服务运行。

## 1. 为什么单独部署

- 当前 `MyTokenTextSplitter` 对 Markdown 结构切分效果较好，但 Office/PDF 依赖解析器保留标题、表格、页码等结构。
- 解析和切片放在 Java 服务内会带来模型体积、OCR 依赖、CPU/内存和升级耦合。
- Docling Serve 提供独立 REST API，当前 Java 服务只需要上传文件、消费 chunks、写 pgvector。

## 2. 镜像方案

官方 `docling-serve-cpu` 镜像约 4.4GB，OrbStack 在拉取后的解压/提交阶段长时间卡住。因此改为：

```text
python:3.11-slim
  + 系统 OCR/图像依赖
  + 本地预生成的 linux/arm64 Python 运行时（docling-target）
  = yu-ai-docling-serve:local
```

运行时由 `build-runtime.sh` 使用 `uv` 生成：

- `torch==2.9.1+cpu`，从阿里云 PyTorch CPU wheel 下载，避免 CUDA 依赖；
- 其余包从阿里云 PyPI 镜像解析；
- 不使用官方 4.4GB 镜像中的模型权重，模型在首次请求时下载到缓存卷。

当前镜像约 **2.37GB**，模型缓存首次 PDF 处理后约 **507MB**。

## 3. 启动

```bash
cd deploy/docker-compose/docling-serve
./build-runtime.sh                       # 首次/升级时执行，生成 docling-target
docker compose up -d --build
```

服务：

- API：`http://127.0.0.1:5001`
- Swagger：`http://127.0.0.1:5001/docs`
- UI：`http://127.0.0.1:5001/ui`
- 健康/文档探针：`GET /docs`

配置：

| 变量 | 值 | 作用 |
| --- | --- | --- |
| `DOCLING_SERVE_LOAD_MODELS_AT_BOOT` | `0` | 先启动 API，模型在首次转换时加载 |
| `DOCLING_SERVE_MAX_SYNC_WAIT` | `600` | 同步切片最长等待 600 秒；扫描件 OCR 可能超过默认 120 秒 |
| `HF_ENDPOINT` | `https://hf-mirror.com` | HuggingFace 镜像 |
| `HF_HUB_DISABLE_XET` | `1` | 避免模型下载被重定向到 xet 存储 |
| `HF_HOME` | `/root/.cache/huggingface` | 模型缓存 |
| `DOCLING_SERVE_ENABLE_UI` | `1` | 启用 `/ui` |

`docling-cache` 卷保存模型和 HuggingFace 缓存，重启容器不会重复下载。

## 4. 已验证能力

### Markdown

```bash
curl -X POST http://127.0.0.1:5001/v1/chunk/hybrid/file \
  -F 'files=@README.md;type=text/markdown' \
  -F 'convert_do_ocr=false' \
  -F 'convert_do_table_structure=false' \
  -F 'convert_from_formats=md'
```

结果：HTTP 200，按章节生成 chunks。

### PDF

```bash
curl -X POST http://127.0.0.1:5001/v1/chunk/hybrid/file \
  -F 'files=@doc.pdf;type=application/pdf' \
  -F 'convert_do_ocr=false' \
  -F 'convert_from_formats=pdf'
```

结果：HTTP 200，示例 PDF 生成 9 个 chunks，保留标题、段落和表格，处理约 1.6 秒（模型已缓存）。

### DOCX

```bash
curl -X POST http://127.0.0.1:5001/v1/chunk/hybrid/file \
  -F 'files=@doc.docx;type=application/vnd.openxmlformats-officedocument.wordprocessingml.document' \
  -F 'convert_do_ocr=false' \
  -F 'convert_from_formats=docx'
```

结果：HTTP 200，按标题生成 chunk，表格被保留。

## 5. 与当前服务集成（下一步）

```text
上传文档
  -> 当前后端保存对象存储
  -> POST /v1/chunk/hybrid/file（multipart）
  -> 读取 chunks[].text / chunks[].meta
  -> 生成 embedding，写入 agent_knowledge_vector
```

建议：

- 只在内网调用 `127.0.0.1:5001`，不要暴露公网。
- 后端增加 `parserVersion`、`chunkerVersion`，便于重建索引。
- 用 `document content sha256` 做解析/切片幂等。
- 对 PDF 的中文 OCR 先用真实扫描件评测 `rapidocr`/`tesseract` 质量。
- 不要把 Docling 的模型下载放在 Spring Boot 进程内。

## 6. 已知边界

- 当前后端尚未接入 Docling Serve，仍是独立可调用服务。
- 运行时按 OrbStack ARM64 预生成；x86_64 需要替换 `--python-platform`、Python ABI 和 torch wheel。
- 首次 PDF/Word 转换会下载模型；离线环境需要提前准备 `docling-cache` 卷。
- 扫描 PDF 的中文 OCR 仍需真实资料评测；表格复杂时建议开启 `selective_ocr` 或调整 table mode。
- 后端同步读取超时需大于 `DOCLING_SERVE_MAX_SYNC_WAIT`（当前后端默认 660 秒）；超时回退本地且 0 切片时，文档会标记失败而不是发布空版本。
- 服务升级时需重新运行 `build-runtime.sh` 并重建镜像。
