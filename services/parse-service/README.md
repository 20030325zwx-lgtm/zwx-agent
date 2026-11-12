# Aster 统一解析服务（parse-service）

Python sidecar：把 PDF / Office 文档解析为 **MinerU 兼容结果包**，供 Java 知识库入库管线复用
（`MineruBlockParser → persistBlocks → StructuredKnowledgeChunker → figureSupport`）。

## 契约

| 端点 | 说明 |
|---|---|
| `GET /health` | 200 `{"status":"ok"}`；不依赖模型，服务启动即可用 |
| `POST /parse` | multipart：`file`（必填）、`engine`（可选，默认 `docling`）；返回 `application/zip` |

zip 内容（与 MinerU 结果包同名同构）：

```
full.md                 # doc.export_to_markdown()
content_list.json       # [{"type":"text|table|image|equation", ...}]
images/p{page}_{n}.png  # 图纸/图片裁剪
```

`content_list.json` 字段约定（Java 侧 `MineruBlockParser` 消费）：

- `type=text`：`text`，`page_idx`（0-based），`bbox:[x0,y0,x1,y1]`（左上原点），标题带 `text_level>0`
- `type=table`：`table_body`（HTML）、`table_caption`（数组）、`table_footnote`
- `type=image`：`img_path`（相对 zip 根）、`img_caption`（数组）、`image_footnote`
- `type=equation`：`text`（LaTeX）
- bbox 缺失时**省略字段**（Java 侧对显式 null 会写出非法 jsonb）

## 本地运行

```bash
cd services/parse-service
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --host 127.0.0.1 --port 8131
```

首次 `/parse` 会下载 Docling 模型到 `HF_HOME`（默认 `~/.cache/huggingface`），需联网。

## 容器

```bash
docker build -t aster-parse-service:local .
docker run --rm -p 8131:8131 -v parse-cache:/root/.cache/huggingface aster-parse-service:local
```

生产 compose 使用 `profiles: ["parse"]` 隔离；后端通过
`APP_DOCUMENT_PARSER_PYTHON_ENABLED=true` + `APP_DOCUMENT_PARSER_PYTHON_BASE_URL=http://parse-service:8131`
启用，失败自动回退 Docling/local。

## 测试

```bash
python3 -m pytest tests/ -q       # 契约单测，不需要 docling
PARSE_SMOKE_PDF=/path/sample.pdf python3 -m pytest tests/test_smoke.py -q   # 可选真实冒烟
```

## 边界

- 仅服务端部署；macOS 桌面端不打包 Python（`application-desktop.yml` 保持 docling 关闭，PDF 走 iText）。
- 离线安装包（`package.sh`）暂不包含本服务；compose profile 隔离保证其不受影响。
- 当前只有 Docling 引擎；`pymupdf` 等快速引擎留作后续。
