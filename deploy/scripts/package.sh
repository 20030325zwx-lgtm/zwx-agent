#!/usr/bin/env sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
VERSION=${VERSION:-0.0.1}
TARGET_PLATFORM=${TARGET_PLATFORM:-linux/amd64}
WITH_BROWSER_MCP=${WITH_BROWSER_MCP:-false}
WITH_ARTIFACT_MCP=${WITH_ARTIFACT_MCP:-false}
PACKAGE_NAME="aster-${VERSION}-$(printf '%s' "$TARGET_PLATFORM" | tr / -)"
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
docker build --platform "$TARGET_PLATFORM" -t "aster-backend:$VERSION" -f "$ROOT_DIR/deploy/Dockerfile.backend" "$ROOT_DIR"
docker build --platform "$TARGET_PLATFORM" -t "aster-frontend:$VERSION" "$ROOT_DIR/frontend"
docker build --platform "$TARGET_PLATFORM" -t "aster-image-mcp:$VERSION" "$ROOT_DIR/services/image-search-mcp"
docker build --platform "$TARGET_PLATFORM" -t "aster-document-mcp:$VERSION" "$ROOT_DIR/services/document-mcp"
# 独立中台服务（与主站同仓库、独立镜像）
docker build --platform "$TARGET_PLATFORM" -t "aster-eval-service:$VERSION" "$ROOT_DIR/services/eval-service"
if [ "$WITH_ARTIFACT_MCP" = "true" ]; then
  echo "构建 artifact 排版引擎（含 LibreOffice，体积较大）..."
  docker build --platform "$TARGET_PLATFORM" -t "aster-artifact-mcp:$VERSION" "$ROOT_DIR/services/artifact-mcp"
fi
docker build --platform "$TARGET_PLATFORM" -t "aster-data-service:$VERSION" "$ROOT_DIR/services/data-service"
if [ "$WITH_BROWSER_MCP" = "true" ]; then
  docker build --platform "$TARGET_PLATFORM" -t "aster-browser-mcp:$VERSION" "$ROOT_DIR/services/browser-mcp"
fi
docker pull --platform "$TARGET_PLATFORM" pgvector/pgvector:pg16
# 离线包内置 Weaviate（服务端向量检索，2026-11-13 起）；镜像源约定见 parse-service-build.sh
WEAVIATE_IMAGE=${WEAVIATE_IMAGE:-docker.m.daocloud.io/semitechnologies/weaviate:1.39.16}
docker pull --platform "$TARGET_PLATFORM" "$WEAVIATE_IMAGE"
docker tag "$WEAVIATE_IMAGE" semitechnologies/weaviate:1.39.16
# 本地免费嵌入（Ollama）：离线包内置镜像；模型首次启动时按文档预热（ollama pull）
OLLAMA_IMAGE=${OLLAMA_IMAGE:-docker.m.daocloud.io/ollama/ollama:latest}
docker pull --platform "$TARGET_PLATFORM" "$OLLAMA_IMAGE"
docker tag "$OLLAMA_IMAGE" ollama/ollama:latest

docker save -o "$IMAGE_DIR/aster-backend.tar" "aster-backend:$VERSION"
docker save -o "$IMAGE_DIR/aster-frontend.tar" "aster-frontend:$VERSION"
docker save -o "$IMAGE_DIR/aster-image-mcp.tar" "aster-image-mcp:$VERSION"
docker save -o "$IMAGE_DIR/aster-document-mcp.tar" "aster-document-mcp:$VERSION"
docker save -o "$IMAGE_DIR/aster-eval-service.tar" "aster-eval-service:$VERSION"
if [ "$WITH_ARTIFACT_MCP" = "true" ]; then
  docker save -o "$IMAGE_DIR/aster-artifact-mcp.tar" "aster-artifact-mcp:$VERSION"
fi
docker save -o "$IMAGE_DIR/aster-data-service.tar" "aster-data-service:$VERSION"
if [ "$WITH_BROWSER_MCP" = "true" ]; then
  docker save -o "$IMAGE_DIR/aster-browser-mcp.tar" "aster-browser-mcp:$VERSION"
fi
docker save -o "$IMAGE_DIR/pgvector-pg16.tar" pgvector/pgvector:pg16
docker save -o "$IMAGE_DIR/weaviate.tar" semitechnologies/weaviate:1.39.16
docker save -o "$IMAGE_DIR/ollama.tar" ollama/ollama:latest

cp "$ROOT_DIR/deploy/docker-compose/docker-compose.yml" "$ROOT_DIR/deploy/docker-compose/.env.example" "$ROOT_DIR/deploy/docker-compose/globe.conf.example" "$ROOT_DIR/deploy/docker-compose/install.sh" "$ROOT_DIR/deploy/docker-compose/stop.sh" "$RELEASE_DIR/"
chmod +x "$RELEASE_DIR/install.sh" "$RELEASE_DIR/stop.sh"
sed -i.bak "s/^IMAGE_TAG=.*/IMAGE_TAG=$VERSION/" "$RELEASE_DIR/.env.example"
# 离线安装不依赖镜像源：weaviate 镜像已随包内置（本地 tag），指向本地镜像名
sed -i.bak "s|^WEAVIATE_IMAGE=.*|WEAVIATE_IMAGE=semitechnologies/weaviate:1.39.16|" "$RELEASE_DIR/.env.example"
sed -i.bak "s|^OLLAMA_IMAGE=.*|OLLAMA_IMAGE=ollama/ollama:latest|" "$RELEASE_DIR/.env.example"
rm -f "$RELEASE_DIR/.env.example.bak"

(cd "$ROOT_DIR/release" && tar -czf "$PACKAGE_NAME.tar.gz" "$PACKAGE_NAME")
echo "安装包：$ROOT_DIR/release/$PACKAGE_NAME.tar.gz"
