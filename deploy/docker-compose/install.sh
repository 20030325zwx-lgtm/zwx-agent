#!/usr/bin/env sh
set -eu

WORKDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
INSTALL_DIR=${ASTER_INSTALL_DIR:-/opt/aster}

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "缺少命令：$1" >&2; exit 1; }
}

env_value() {
  key="$1"
  sed -n "s/^${key}=//p" "$INSTALL_DIR/.env" | tail -n 1
}

require_value() {
  value=$(env_value "$1")
  case "$value" in
    ''|YOUR_*|CHANGE_ME_*) return 1 ;;
  esac
}

# 从服务器外部配置文件（globe.conf）读取值
conf_value() {
  sed -n "s/^${1}=//p" "$GLOBE_CONF" 2>/dev/null | tail -n 1
}

require_command docker
require_command curl
docker compose version >/dev/null 2>&1 || { echo "需要 Docker Compose v2（docker compose）" >&2; exit 1; }

mkdir -p "$INSTALL_DIR/images" "$INSTALL_DIR/temp"
if [ "$WORKDIR" != "$INSTALL_DIR" ]; then
  cp "$WORKDIR/docker-compose.yml" "$WORKDIR/.env.example" "$WORKDIR/globe.conf.example" "$WORKDIR/install.sh" "$WORKDIR/stop.sh" "$INSTALL_DIR/"
  cp "$WORKDIR"/images/*.tar "$INSTALL_DIR/images/"
  chmod +x "$INSTALL_DIR/install.sh" "$INSTALL_DIR/stop.sh"
fi

if [ ! -f "$INSTALL_DIR/.env" ]; then
  if [ -f "$WORKDIR/.env" ]; then
    cp "$WORKDIR/.env" "$INSTALL_DIR/.env"
  else
    cp "$INSTALL_DIR/.env.example" "$INSTALL_DIR/.env"
    chmod 600 "$INSTALL_DIR/.env"
    echo "已创建 $INSTALL_DIR/.env，请填写必填配置后重新执行安装脚本。" >&2
    exit 1
  fi
fi
chmod 600 "$INSTALL_DIR/.env"

if [ ! -f "$INSTALL_DIR/.env" ]; then
  echo "未找到 $INSTALL_DIR/.env" >&2
  exit 1
fi

require_value POSTGRES_PASSWORD || { echo ".env 中必须填写 POSTGRES_PASSWORD" >&2; exit 1; }

# —— 服务器外部配置文件（globe.conf）：API Key、JWT 密钥等由用户在服务器上维护 ——
GLOBE_CONF=$(env_value APP_GLOBE_CONF)
[ -n "$GLOBE_CONF" ] || GLOBE_CONF=/home/globe.conf

if [ ! -f "$GLOBE_CONF" ]; then
  cp "$INSTALL_DIR/globe.conf.example" "$GLOBE_CONF"
  chmod 600 "$GLOBE_CONF"
  echo "已创建 $GLOBE_CONF，请填写 app.security.jwt-secret 后重新执行安装脚本。" >&2
  exit 1
fi
chmod 600 "$GLOBE_CONF" 2>/dev/null || true
JWT_RESOLVED=$(conf_value 'app\.security\.jwt-secret')
case "$JWT_RESOLVED" in
  ''|YOUR_*|CHANGE_ME_*) echo "请在 $GLOBE_CONF 中填写 app.security.jwt-secret（至少 32 位随机字符串）" >&2; exit 1 ;;
esac

# 图片 MCP 直接读取 .env，因此 DashScope Key 必须写在 .env 中
require_value DASHSCOPE_API_KEY || { echo ".env 中必须填写 DASHSCOPE_API_KEY（图片 MCP 使用）" >&2; exit 1; }
require_value MINERU_TOKEN || { echo ".env 中必须填写 MINERU_TOKEN（知识库入库与文档解析使用）" >&2; exit 1; }
BROWSER_MCP_ENABLED_VALUE=$(env_value BROWSER_MCP_ENABLED)
if [ "$BROWSER_MCP_ENABLED_VALUE" = "true" ]; then
  require_value PUBLIC_BASE_URL || { echo "启用网页截图时必须在 .env 填写 PUBLIC_BASE_URL，例如 http://<服务器IP>:8080" >&2; exit 1; }
fi

chmod 700 "$INSTALL_DIR/temp"

for image_archive in "$INSTALL_DIR"/images/*.tar; do
  [ -f "$image_archive" ] || { echo "未找到镜像文件：$INSTALL_DIR/images" >&2; exit 1; }
  docker load -i "$image_archive"
done

docker compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" up -d
if [ "$BROWSER_MCP_ENABLED_VALUE" = "true" ]; then
  docker compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" --profile browser up -d browser-mcp
fi

# 等待 PostgreSQL 可用后注册内置 MCP 服务；后端容器通过服务名访问。
POSTGRES_USER_VALUE=$(env_value POSTGRES_USER)
POSTGRES_DB_VALUE=$(env_value POSTGRES_DB)
for i in $(seq 1 30); do
  if docker compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" \
      exec -T postgres pg_isready -U "$POSTGRES_USER_VALUE" -d "$POSTGRES_DB_VALUE" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
docker compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" \
  exec -T postgres psql -U "$POSTGRES_USER_VALUE" -d "$POSTGRES_DB_VALUE" <<'SQL'
INSERT INTO mcp_server_configuration (tenant_id, name, endpoint, enabled, auth_type, allowed_tools)
VALUES
  ('default', '图片理解', 'http://image-mcp:8127', true, 'NONE', '["describe_image","ocr_image","extract_table"]'::jsonb),
  ('default', '文档解析', 'http://document-mcp:8128', true, 'NONE', '["parse_document"]'::jsonb)
ON CONFLICT (tenant_id, name) DO UPDATE SET
  endpoint = EXCLUDED.endpoint,
  enabled = true,
  auth_type = 'NONE',
  allowed_tools = EXCLUDED.allowed_tools,
  updated_at = CURRENT_TIMESTAMP;
SQL
if [ "$BROWSER_MCP_ENABLED_VALUE" = "true" ]; then
  docker compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" \
    exec -T postgres psql -U "$POSTGRES_USER_VALUE" -d "$POSTGRES_DB_VALUE" <<'SQL'
INSERT INTO mcp_server_configuration (tenant_id, name, endpoint, enabled, auth_type, allowed_tools)
VALUES ('default', '网页截图', 'http://browser-mcp:8129', true, 'NONE', '["screenshot_page"]'::jsonb)
ON CONFLICT (tenant_id, name) DO UPDATE SET
  endpoint = EXCLUDED.endpoint,
  enabled = true,
  auth_type = 'NONE',
  allowed_tools = EXCLUDED.allowed_tools,
  updated_at = CURRENT_TIMESTAMP;
SQL
fi

for i in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$(env_value ASTER_PORT)/api/health" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

echo "Aster 已启动：http://<服务器地址>:$(env_value ASTER_PORT)"
if [ "$BROWSER_MCP_ENABLED_VALUE" = "true" ]; then
  echo "网页截图已启用，PUBLIC_BASE_URL=$(env_value PUBLIC_BASE_URL)，请确认该地址可从浏览器访问。"
fi
