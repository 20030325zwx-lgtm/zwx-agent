# 基础镜像与包源可通过 build-arg 覆盖：
# 服务器（国内）无法访问 Docker Hub 时，用
#   --build-arg BASE_IMAGE=docker.m.daocloud.io/library/python:3.11-slim
#   --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG BASE_IMAGE=python:3.11-slim
FROM ${BASE_IMAGE}

ARG PIP_INDEX_URL=https://pypi.org/simple
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_ENDPOINT=https://hf-mirror.com \
    HF_HOME=/root/.cache/huggingface \
    HF_HUB_DISABLE_XET=1

# Docling 依赖的系统库 + Tesseract 中文 OCR
RUN apt-get update && apt-get install -y --no-install-recommends \
      libgl1 libglib2.0-0 libgomp1 libsm6 libxext6 libxrender1 \
      tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# CPU 版 torch/torchvision：PyPI 的 linux wheel 自带 CUDA（镜像大 ~2GB），
# +cpu 本地版本只存在于 PyTorch 官方 CPU 索引；其余依赖仍从 PIP_INDEX_URL 解析。
RUN pip install --no-cache-dir --index-url "${PIP_INDEX_URL}" \
      --extra-index-url "${TORCH_INDEX_URL}" \
      "torch==2.9.1+cpu" "torchvision==0.24.1+cpu"

COPY requirements.txt .
RUN pip install --no-cache-dir --index-url "${PIP_INDEX_URL}" -r requirements.txt

COPY app ./app

EXPOSE 8131
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8131/health', timeout=3).status == 200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8131"]
