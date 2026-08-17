#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
VERSION=${VERSION:-0.0.1}
TARGET_PLATFORM=${TARGET_PLATFORM:-linux/amd64}
PACKAGE_NAME="zwx-agent-${VERSION}-$(printf '%s' "$TARGET_PLATFORM" | tr / -)"
RELEASE_DIR="$ROOT_DIR/release/$PACKAGE_NAME"
IMAGE_DIR="$RELEASE_DIR/images"

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1" >&2; exit 1; }
}

require_command docker
require_command tar

rm -rf "$RELEASE_DIR"
mkdir -p "$IMAGE_DIR"

echo "构建后端 JAR..."
(cd "$ROOT_DIR" && sh ./mvnw -q -DskipTests clean package)

echo "构建前端静态资源..."
(cd "$ROOT_DIR/zwx-agent-frontend" && npm ci && npm run build)

echo "构建 ${TARGET_PLATFORM} 应用镜像..."
docker build --platform "$TARGET_PLATFORM" -t "zwx-agent-backend:$VERSION" "$ROOT_DIR"
docker build --platform "$TARGET_PLATFORM" -t "zwx-agent-frontend:$VERSION" "$ROOT_DIR/zwx-agent-frontend"
docker pull --platform "$TARGET_PLATFORM" pgvector/pgvector:pg16

docker save -o "$IMAGE_DIR/zwx-agent-backend.tar" "zwx-agent-backend:$VERSION"
docker save -o "$IMAGE_DIR/zwx-agent-frontend.tar" "zwx-agent-frontend:$VERSION"
docker save -o "$IMAGE_DIR/pgvector-pg16.tar" pgvector/pgvector:pg16

cp "$ROOT_DIR/docker-compose/docker-compose.yml" "$ROOT_DIR/docker-compose/.env.example" "$ROOT_DIR/docker-compose/install.sh" "$ROOT_DIR/docker-compose/stop.sh" "$RELEASE_DIR/"
chmod +x "$RELEASE_DIR/install.sh" "$RELEASE_DIR/stop.sh"
sed -i.bak "s/^IMAGE_TAG=.*/IMAGE_TAG=$VERSION/" "$RELEASE_DIR/.env.example"
rm -f "$RELEASE_DIR/.env.example.bak"

(cd "$ROOT_DIR/release" && tar -czf "$PACKAGE_NAME.tar.gz" "$PACKAGE_NAME")
echo "安装包：$ROOT_DIR/release/$PACKAGE_NAME.tar.gz"
