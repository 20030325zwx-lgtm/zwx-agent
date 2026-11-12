#!/bin/bash
# 在服务器上幂等构建 parse-service 镜像。
# - 镜像已存在：直接返回（重跑安全）；
# - 已有构建进程：等待它完成（SSH 掉线后重试不会重复构建）；
# - 构建日志：/opt/aster/parse-service-src/parse-build.log（失败时输出尾部）。
set -euo pipefail

VERSION="${1:?usage: parse-service-build.sh <version>}"
SRC_DIR="/opt/aster/parse-service-src"
IMAGE="aster-parse-service:${VERSION}"

cd "$SRC_DIR"

if docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "PARSE_IMAGE_CACHED"
  exit 0
fi

if ! pgrep -f "^docker build .*${IMAGE}" >/dev/null 2>&1; then
  nohup docker build --progress=plain \
    --build-arg BASE_IMAGE=docker.m.daocloud.io/library/python:3.11-slim \
    --build-arg PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple \
    -t "$IMAGE" . > parse-build.log 2>&1 &
fi

while pgrep -f "^docker build .*${IMAGE}" >/dev/null 2>&1; do
  sleep 10
done

if docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "PARSE_IMAGE_BUILT"
else
  echo "PARSE_BUILD_FAILED"
  tail -60 parse-build.log 2>/dev/null || true
  exit 1
fi
