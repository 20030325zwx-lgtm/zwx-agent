# 基础镜像与包源可通过 build-arg 覆盖：
# 服务器（国内）无法访问 Docker Hub 时，用
#   --build-arg BASE_IMAGE=docker.m.daocloud.io/library/python:3.11-slim
#   --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG BASE_IMAGE=python:3.11-slim
FROM ${BASE_IMAGE}

ARG PIP_INDEX_URL=https://pypi.org/simple
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
# Debian 官方源在国内可能极慢；默认切阿里云镜像（可用 build-arg 覆盖）
ARG APT_MIRROR=mirrors.aliyun.com

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_ENDPOINT=https://hf-mirror.com \
    HF_HOME=/root/.cache/huggingface \
    HF_HUB_DISABLE_XET=1

# Docling 依赖的系统库 + Tesseract 中文 OCR + 下载工具
RUN if [ -n "${APT_MIRROR}" ]; then \
      sed -i "s|deb.debian.org|${APT_MIRROR}|g; s|security.debian.org|${APT_MIRROR}|g" \
        /etc/apt/sources.list.d/debian.sources 2>/dev/null || true; \
      sed -i "s|deb.debian.org|${APT_MIRROR}|g" /etc/apt/sources.list 2>/dev/null || true; \
    fi; \
    apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates \
      libgl1 libglib2.0-0 libgomp1 libsm6 libxext6 libxrender1 \
      tesseract-ocr tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# CPU 版 torch/torchvision：
# - PyPI 的 linux wheel 自带 CUDA（镜像大 ~2GB）；
# - amd64（服务器）走阿里云 wheel 直链，避免国际带宽；
# - arm64（本地开发）走 PyTorch 官方 CPU 索引（+cpu 版本只存在于该索引）。
ARG TARGETARCH
RUN set -e; \
    if [ "${TARGETARCH:-amd64}" = "amd64" ]; then \
      TORCH_WHEEL="torch-2.9.1+cpu-cp311-cp311-manylinux_2_28_x86_64.whl"; \
      TV_WHEEL="torchvision-0.24.1+cpu-cp311-cp311-manylinux_2_28_x86_64.whl"; \
      curl -fsSL -o "/tmp/${TORCH_WHEEL}" \
        "https://mirrors.aliyun.com/pytorch-wheels/cpu/torch-2.9.1%2Bcpu-cp311-cp311-manylinux_2_28_x86_64.whl"; \
      curl -fsSL -o "/tmp/${TV_WHEEL}" \
        "https://mirrors.aliyun.com/pytorch-wheels/cpu/torchvision-0.24.1%2Bcpu-cp311-cp311-manylinux_2_28_x86_64.whl"; \
      pip install --no-cache-dir --index-url "${PIP_INDEX_URL}" \
        "/tmp/${TORCH_WHEEL}" "/tmp/${TV_WHEEL}"; \
      rm -f "/tmp/${TORCH_WHEEL}" "/tmp/${TV_WHEEL}"; \
    elif [ "${TARGETARCH}" = "arm64" ]; then \
      pip install --no-cache-dir --index-url "${PIP_INDEX_URL}" \
        --extra-index-url "${TORCH_INDEX_URL}" \
        "torch==2.9.1+cpu" "torchvision==0.24.1+cpu"; \
    else \
      echo "unsupported architecture: ${TARGETARCH}" >&2; exit 1; \
    fi

COPY requirements.txt .
# uv 解析 ML 依赖树比 pip 快一个数量级（pip 在 transformers 多版本间回溯极慢）
RUN pip install --no-cache-dir uv \
    && uv pip install --system --default-index "${PIP_INDEX_URL}" -r requirements.txt

COPY app ./app

EXPOSE 8131
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8131/health', timeout=3).status == 200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8131"]
