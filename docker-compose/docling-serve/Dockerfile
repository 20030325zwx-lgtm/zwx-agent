FROM python:3.11-slim

ENV PYTHONUNBUFFERED=1 \
    HF_ENDPOINT=https://hf-mirror.com \
    HF_HOME=/root/.cache/huggingface \
    HF_HUB_DISABLE_XET=1 \
    HF_HUB_ENABLE_HF_TRANSFER=0 \
    PYTHONPATH=/opt/docling \
    PATH=/opt/docling/bin:$PATH \
    DOCLING_SERVE_ENABLE_UI=1 \
    DOCLING_SERVE_LOAD_MODELS_AT_BOOT=0

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libgl1 \
        libglib2.0-0 \
        libgomp1 \
        libsm6 \
        libxext6 \
        libxrender1 \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
    && rm -rf /var/lib/apt/lists/*

# Run ./build-runtime.sh first. The directory is generated locally and is
# intentionally ignored by git: it contains linux/arm64 wheels for this image.
COPY docling-target /opt/docling

EXPOSE 5001

CMD ["python", "-m", "docling_serve", "run", "--host", "0.0.0.0", "--port", "5001", "--enable-ui"]
