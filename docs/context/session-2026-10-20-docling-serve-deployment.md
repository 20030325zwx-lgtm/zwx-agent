# 会话摘要：Docling Serve 本地部署（2026-10-20）

## 结果

- 已在 OrbStack 部署独立 Docling Serve：容器名 `yu-ai-docling-serve`，只绑定 `127.0.0.1:5001`。
- API 文档 `http://127.0.0.1:5001/docs` 返回 200，UI 为 `/ui`。
- 当前镜像 `yu-ai-docling-serve:local` 约 2.37GB。
- 模型缓存卷 `docling-cache` 首次 PDF 处理后约 507MB；Docker volumes 总计约 928MB。

## 镜像方案

- 官方 `docling-serve-cpu` 4.4GB，OrbStack 拉取后卡在解压/提交阶段，`docker images` 长时间没有结果。
- 改为 `python:3.11-slim` + 预生成 linux/arm64 运行时：
  - `uv pip install --target docling-target --python-platform aarch64-unknown-linux-gnu --python-version 3.11 ... docling-serve`
  - `torch==2.9.1+cpu` 使用阿里云 PyTorch CPU wheel，避免 CUDA 依赖。
- 运行时目录 `docling-target/` 和 wheel 不进入 Git。

## 运行配置

- `DOCLING_SERVE_LOAD_MODELS_AT_BOOT=0`：先启动 API，首次请求加载模型。
- `HF_ENDPOINT=https://hf-mirror.com`
- `HF_HUB_DISABLE_XET=1`
- `HF_HUB_ENABLE_HF_TRANSFER=0`
- `HF_HOME=/root/.cache/huggingface`
- `docker-compose/docling-serve/docker-compose.yml` 已通过 `docker compose config` 校验。

## 验证

- Markdown：
  - `POST /v1/chunk/hybrid/file`，`convert_from_formats=md`，`convert_do_ocr=false`，`convert_do_table_structure=false`；
  - HTTP 200，按章节生成 2 个 chunks。
- PDF：
  - 使用 `temp/tools/shared/pdf/静安约会计划.pdf`；
  - HTTP 200，9 个 chunks，保留标题、段落和表格，模型已缓存后约 1.6 秒。
- DOCX：
  - 使用 `textutil` 从 Markdown 生成测试 docx；
  - HTTP 200，正常返回 chunk 和 Markdown 表格。

## 磁盘清理

- 清理了 `/tmp/docling-target`、重复构建上下文、torch wheel 约 4GB。
- 清理 Docker build cache 约 3.6GB。
- 当前额外占用：镜像 2.37GB + 模型缓存约 0.5GB。

## 新增文件

- `docker-compose/docling-serve/Dockerfile`
- `docker-compose/docling-serve/docker-compose.yml`
- `docker-compose/docling-serve/build-runtime.sh`
- `docker-compose/docling-serve/constraints.txt`
- `docker-compose/docling-serve/.dockerignore`
- `docker-compose/docling-serve/.gitignore`
- `docs/design/2026-10-20-docling-serve-deployment.md`

## README / 文档

- 根 `README.md` 新增“独立文档解析与切片服务（Docling Serve）”章节。
- `docs/design/README.md` 已加入部署设计索引。

## 遗留事项

- 当前 Spring Boot 后端尚未调用 Docling Serve，仍是独立服务。
- x86_64 需要调整 `build-runtime.sh` 的 platform、Python ABI 和 torch wheel。
- 中文扫描 PDF 的 OCR 质量需要用真实资料评测。
- 首次 PDF/Word 转换会下载模型；离线部署需预置 `docling-cache`。
- 本轮改动尚未提交。
