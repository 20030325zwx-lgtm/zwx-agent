#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# OrbStack on Apple Silicon is linux/arm64. For x86_64 hosts, change the
# platform, Python ABI and torch wheel below accordingly.
TORCH_WHEEL="torch-2.9.1+cpu-cp311-cp311-manylinux_2_28_aarch64.whl"
TORCH_URL="https://mirrors.aliyun.com/pytorch-wheels/cpu/${TORCH_WHEEL}"

if ! command -v uv >/dev/null 2>&1; then
  echo "uv is required: https://docs.astral.sh/uv/" >&2
  exit 1
fi

if [ ! -f "${TORCH_WHEEL}" ]; then
  echo "Downloading ${TORCH_WHEEL}"
  curl -fL --retry 3 -o "${TORCH_WHEEL}" "${TORCH_URL}"
fi

rm -rf docling-target
uv pip install \
  --target docling-target \
  --python-platform aarch64-unknown-linux-gnu \
  --python-version 3.11 \
  --default-index https://mirrors.aliyun.com/pypi/simple/ \
  --find-links . \
  --constraint constraints.txt \
  docling-serve

echo "Runtime ready at $(pwd)/docling-target"
