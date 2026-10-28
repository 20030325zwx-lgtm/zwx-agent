#!/bin/sh
# 阶段 D 端到端验证：按 main.cjs 的序列本机模拟（initdb -> single 建库 -> pg 启动 -> desktop profile 后端 -> 回环令牌认证）
set -eu
PGSERVER=${1:-/Users/zhuwenxuan/Desktop/agent/yu-ai-agent/desktop/pgserver}
USER_DATA=/tmp/aster-desktop-userdata
PG_PORT=15433
BACKEND_PORT=18233
LOCAL_TOKEN=$(openssl rand -hex 32)
ADMIN_PASSWORD=$(openssl rand -base64 18 | tr '+/' '-_')

rm -rf "$USER_DATA"
# 清理上次异常退出残留的 postmaster（持有数据目录锁会阻塞 initdb）
pkill -f "aster-desktop-userdata/pgdata" 2>/dev/null || true
sleep 1
mkdir -p "$USER_DATA/tmp" "$USER_DATA/logs"
PG_BIN="$PGSERVER/bin"
export LC_ALL=C
export DYLD_LIBRARY_PATH="$PGSERVER/lib"

echo "==> 1. initdb"
"$PG_BIN/initdb" -D "$USER_DATA/pgdata" -U aster -A trust -E UTF8 > "$USER_DATA/logs/initdb.log" 2>&1
echo "    ok"

echo "==> 2. single-user 建库（须在服务器启动前，无 psql/createdb 路径）"
"$PG_BIN/postgres" --single -D "$USER_DATA/pgdata" -E postgres <<SQL > "$USER_DATA/logs/createdb.log" 2>&1
CREATE DATABASE aster OWNER aster;
SQL
grep -q "CREATE DATABASE" "$USER_DATA/logs/createdb.log" && echo "    ok（日志确认建库）" || echo "    警告：日志未见 CREATE DATABASE，检查 $USER_DATA/logs/createdb.log"

echo "==> 3. 启动 PostgreSQL（端口 $PG_PORT）"
"$PG_BIN/pg_ctl" -D "$USER_DATA/pgdata" -l "$USER_DATA/logs/postgres.log" \
  -o "-p $PG_PORT -c listen_addresses=127.0.0.1 -c unix_socket_directories=$USER_DATA/tmp" \
  -w -t 30 start > /dev/null 2>&1
echo "    ok"

echo "==> 4. 启动后端（desktop profile，端口 $BACKEND_PORT）"
DYLD_LIBRARY_PATH= /usr/libexec/java_home -v 21 --exec java -jar /Users/zhuwenxuan/Desktop/agent/yu-ai-agent/target/aster-agent-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=desktop \
  --server.port=$BACKEND_PORT \
  --management.server.port=18234 \
  --spring.datasource.url=jdbc:postgresql://127.0.0.1:$PG_PORT/aster \
  --spring.datasource.username=aster \
  --spring.datasource.password=hosted \
  --app.desktop.loopback-token=$LOCAL_TOKEN \
  --app.security.admin-password=$ADMIN_PASSWORD \
  > "$USER_DATA/logs/backend.log" 2>&1 &
BACKEND_PID=$!
trap 'kill $BACKEND_PID 2>/dev/null || true; "$PG_BIN/pg_ctl" -D "$USER_DATA/pgdata" -m fast stop > /dev/null 2>&1 || true' EXIT

for i in $(seq 1 30); do
  sleep 4
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -m 3 "http://127.0.0.1:$BACKEND_PORT/api/health" 2>/dev/null || true)
  [ "$CODE" = "200" ] && break
done
if [ "$CODE" != "200" ]; then echo "    启动失败"; tail -20 "$USER_DATA/logs/backend.log"; exit 1; fi
echo "    ok（health 200）"

echo "==> 5. 回环令牌认证"
WITH_TOKEN=$(curl -s -o /dev/null -w "%{http_code}" -m 5 "http://127.0.0.1:$BACKEND_PORT/api/settings/catalog" -H "X-Aster-Local-Token: $LOCAL_TOKEN")
WITHOUT_TOKEN=$(curl -s -o /dev/null -w "%{http_code}" -m 5 "http://127.0.0.1:$BACKEND_PORT/api/settings/catalog")
WRONG_TOKEN=$(curl -s -o /dev/null -w "%{http_code}" -m 5 "http://127.0.0.1:$BACKEND_PORT/api/settings/catalog" -H "X-Aster-Local-Token: deadbeef")
echo "    带令牌: $WITH_TOKEN（期望 200）"
echo "    无令牌: $WITHOUT_TOKEN（期望 401）"
echo "    错令牌: $WRONG_TOKEN（期望 401）"
WHOAMI=$(curl -s -m 5 "http://127.0.0.1:$BACKEND_PORT/api/settings/catalog" -H "X-Aster-Local-Token: $LOCAL_TOKEN")
echo "    目录响应: $(echo "$WHOAMI" | head -c 120)"
if [ "$WITH_TOKEN" = "200" ] && [ "$WITHOUT_TOKEN" = "401" ] && [ "$WRONG_TOKEN" = "401" ]; then
  echo "    回环令牌验证通过"
else
  echo "==> 回环令牌验证失败"
  exit 1
fi

echo "==> 6. 本机目录授权链路"
GRANT_DIR="$USER_DATA/grant-root/myproject"
mkdir -p "$GRANT_DIR"
OTHER_DIR="$USER_DATA/other-project"
mkdir -p "$OTHER_DIR"
GRANT_JSON=$(printf '{"paths":["%s/grant-root"]}' "$USER_DATA")
SET_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:$BACKEND_PORT/api/ai/desktop/grants" \
  -H "X-Aster-Local-Token: $LOCAL_TOKEN" -H "Content-Type: application/json" -d "$GRANT_JSON")
GRANTED_BODY=$(curl -s -X POST "http://127.0.0.1:$BACKEND_PORT/api/ai/projects/select" \
  -H "X-Aster-Local-Token: $LOCAL_TOKEN" -H "Content-Type: application/json" \
  -d "{\"path\":\"$GRANT_DIR\"}")
GRANTED_SELECT=$(echo "$GRANTED_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id','') and 200 or 400)" 2>/dev/null || echo 400)
GRANTED_ERR=$(echo "$GRANTED_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('error',''))" 2>/dev/null)
DENIED_SELECT=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://127.0.0.1:$BACKEND_PORT/api/ai/projects/select" \
  -H "X-Aster-Local-Token: $LOCAL_TOKEN" -H "Content-Type: application/json" \
  -d "{\"path\":\"$OTHER_DIR\"}")
echo "    同步授权: $SET_CODE（期望 200）"
echo "    授权目录绑定: $GRANTED_SELECT（期望 200）${GRANTED_ERR:+ 失败原因: $GRANTED_ERR}"
echo "    未授权目录绑定: $DENIED_SELECT（期望 400）"
if [ "$SET_CODE" = "200" ] && [ "$GRANTED_SELECT" = "200" ] && [ "$DENIED_SELECT" = "400" ]; then
  echo "==> 验证通过"
else
  echo "==> 验证失败"
  exit 1
fi
