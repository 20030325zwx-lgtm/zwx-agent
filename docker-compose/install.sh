#!/usr/bin/env sh
set -eu

WORKDIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
INSTALL_DIR=${ZWX_AGENT_INSTALL_DIR:-/opt/zwx-agent}

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
  echo "已创建 $GLOBE_CONF，请填写 spring.ai.dashscope.api-key 与 app.security.jwt-secret 后重新执行安装脚本。" >&2
  exit 1
fi
chmod 600 "$GLOBE_CONF" 2>/dev/null || true

# DashScope 密钥二选一：.env 的 DASHSCOPE_API_KEY 或 globe.conf 的 spring.ai.dashscope.api-key
DASHSCOPE_RESOLVED=$(env_value DASHSCOPE_API_KEY)
case "$DASHSCOPE_RESOLVED" in ''|YOUR_*|CHANGE_ME_*) DASHSCOPE_RESOLVED='' ;; esac
if [ -z "$DASHSCOPE_RESOLVED" ]; then
  DASHSCOPE_RESOLVED=$(conf_value 'spring\.ai\.dashscope\.api-key')
fi
case "$DASHSCOPE_RESOLVED" in
  ''|YOUR_*|CHANGE_ME_*) echo "必须在 .env 的 DASHSCOPE_API_KEY 或 $GLOBE_CONF 的 spring.ai.dashscope.api-key 中至少填写一处" >&2; exit 1 ;;
esac

chmod 700 "$INSTALL_DIR/temp"

for image_archive in "$INSTALL_DIR"/images/*.tar; do
  [ -f "$image_archive" ] || { echo "未找到镜像文件：$INSTALL_DIR/images" >&2; exit 1; }
  docker load -i "$image_archive"
done

docker compose --env-file "$INSTALL_DIR/.env" -f "$INSTALL_DIR/docker-compose.yml" up -d
echo "ZWX Agent 已启动：http://<服务器地址>:$(env_value ZWX_AGENT_PORT)"
