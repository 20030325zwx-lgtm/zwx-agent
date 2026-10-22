#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
VERSION=${VERSION:-0.0.1}
TARGET_PLATFORM=${TARGET_PLATFORM:-linux/amd64}
WITH_BROWSER_MCP=${WITH_BROWSER_MCP:-false}
PACKAGE_NAME="zwx-agent-${VERSION}-$(printf '%s' "$TARGET_PLATFORM" | tr / -)"
RELEASE_DIR="$ROOT_DIR/release/$PACKAGE_NAME"
IMAGE_DIR="$RELEASE_DIR/images"

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1" >&2; exit 1; }
}

require_command docker
require_command tar
require_command mvn

rm -rf "$RELEASE_DIR"
mkdir -p "$IMAGE_DIR"

echo "构建后端 JAR..."
(cd "$ROOT_DIR" && mvn -q -DskipTests clean package)

echo "构建前端静态资源..."
(cd "$ROOT_DIR/frontend" && npm ci && npm run build)

echo "构建 ${TARGET_PLATFORM} 应用镜像..."
docker build --platform "$TARGET_PLATFORM" -t "zwx-agent-backend:$VERSION" -f "$ROOT_DIR/deploy/Dockerfile.backend" "$ROOT_DIR"
docker build --platform "$TARGET_PLATFORM" -t "zwx-agent-frontend:$VERSION" "$ROOT_DIR/frontend"
docker build --platform "$TARGET_PLATFORM" -t "zwx-image-search-mcp-server:$VERSION" "$ROOT_DIR/services/image-search-mcp"
docker build --platform "$TARGET_PLATFORM" -t "zwx-document-mcp-server:$VERSION" "$ROOT_DIR/services/document-mcp"
if [ "$WITH_BROWSER_MCP" = "true" ]; then
  docker build --platform "$TARGET_PLATFORM" -t "zwx-browser-mcp-server:$VERSION" "$ROOT_DIR/services/browser-mcp"
fi
docker pull --platform "$TARGET_PLATFORM" pgvector/pgvector:pg16

docker save -o "$IMAGE_DIR/zwx-agent-backend.tar" "zwx-agent-backend:$VERSION"
docker save -o "$IMAGE_DIR/zwx-agent-frontend.tar" "zwx-agent-frontend:$VERSION"
docker save -o "$IMAGE_DIR/zwx-image-search-mcp-server.tar" "zwx-image-search-mcp-server:$VERSION"
docker save -o "$IMAGE_DIR/zwx-document-mcp-server.tar" "zwx-document-mcp-server:$VERSION"
if [ "$WITH_BROWSER_MCP" = "true" ]; then
  docker save -o "$IMAGE_DIR/zwx-browser-mcp-server.tar" "zwx-browser-mcp-server:$VERSION"
fi
docker save -o "$IMAGE_DIR/pgvector-pg16.tar" pgvector/pgvector:pg16

cp "$ROOT_DIR/deploy/docker-compose/docker-compose.yml" "$ROOT_DIR/deploy/docker-compose/.env.example" "$ROOT_DIR/deploy/docker-compose/globe.conf.example" "$ROOT_DIR/deploy/docker-compose/install.sh" "$ROOT_DIR/deploy/docker-compose/stop.sh" "$RELEASE_DIR/"
chmod +x "$RELEASE_DIR/install.sh" "$RELEASE_DIR/stop.sh"
sed -i.bak "s/^IMAGE_TAG=.*/IMAGE_TAG=$VERSION/" "$RELEASE_DIR/.env.example"
rm -f "$RELEASE_DIR/.env.example.bak"

(cd "$ROOT_DIR/release" && tar -czf "$PACKAGE_NAME.tar.gz" "$PACKAGE_NAME")
echo "安装包：$ROOT_DIR/release/$PACKAGE_NAME.tar.gz"
