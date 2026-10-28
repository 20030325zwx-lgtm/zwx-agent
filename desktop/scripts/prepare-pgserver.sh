#!/bin/sh
# 组装桌面捆绑的 PostgreSQL + pgvector 到 desktop/pgserver/。
# 流程与阶段 A 验证一致（docs/design/2026-10-28-desktop-phase-a-runtime-model-contract.md 第 2~3 节）：
#   1. Zonky darwin-arm64v8 二进制（优先阿里云镜像，失败回退 Maven Central），SHA1 校验
#   2. 同版本源码树 configure 后，用仿造 pg_config 编译 pgvector（版本必须与二进制主版本一致）
#   3. 扩展安装到 Debian 风格 share/postgresql/extension，产物用 .dylib 命名（该构建的 DLSUFFIX）
#   4. 重复 dylib 符号链接去重（节省约 178MB）
# 用法：sh scripts/prepare-pgserver.sh [--pg-version 18.6.0] [--pgvector-version 0.8.7]
set -eu

PG_VERSION=${PG_VERSION:-18.6.0}
PGVECTOR_VERSION=${PGVECTOR_VERSION:-0.8.7}
while [ $# -gt 0 ]; do
  case "$1" in
    --pg-version) PG_VERSION=$2; shift 2 ;;
    --pgvector-version) PGVECTOR_VERSION=$2; shift 2 ;;
    *) echo "未知参数：$1"; exit 1 ;;
  esac
done

DESKTOP_DIR=$(cd "$(dirname "$0")/.." && pwd)
OUTPUT_DIR="$DESKTOP_DIR/pgserver"
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT
PG_MAJOR=${PG_VERSION%%.*}
echo "==> PostgreSQL $PG_VERSION + pgvector $PGVECTOR_VERSION -> $OUTPUT_DIR"

fetch() {
  # fetch <输出文件> <url...>：逐个候选源尝试
  out=$1
  shift
  for url in "$@"; do
    echo "    下载 $url" >&2
    if curl -fsSL --retry 2 -m 600 -o "$out" "$url"; then return 0; fi
  done
  return 1
}

# ---------------------------------------------------------------- 1. 二进制
ZONKY_BASE_ALIYUN="https://maven.aliyun.com/repository/central/io/zonky/test/postgres/embedded-postgres-binaries-darwin-arm64v8/$PG_VERSION"
ZONKY_BASE_CENTRAL="https://repo1.maven.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-darwin-arm64v8/$PG_VERSION"
JAR_NAME="embedded-postgres-binaries-darwin-arm64v8-$PG_VERSION.jar"
mkdir -p "$WORK_DIR/bundle" "$OUTPUT_DIR"
fetch "$WORK_DIR/pg.jar" "$ZONKY_BASE_ALIYUN/$JAR_NAME" "$ZONKY_BASE_CENTRAL/$JAR_NAME"
EXPECTED_SHA1=$(fetch "$WORK_DIR/pg.jar.sha1" "$ZONKY_BASE_ALIYUN/$JAR_NAME.sha1" "$ZONKY_BASE_CENTRAL/$JAR_NAME.sha1" && tr -d ' \n' < "$WORK_DIR/pg.jar.sha1" || true)
if [ -n "$EXPECTED_SHA1" ]; then
  ACTUAL_SHA1=$(shasum -a 1 "$WORK_DIR/pg.jar" | awk '{print $1}')
  [ "$ACTUAL_SHA1" = "$EXPECTED_SHA1" ] || { echo "SHA1 校验失败"; exit 1; }
  echo "    SHA1 校验通过"
fi
(cd "$WORK_DIR/bundle" && unzip -oq "$WORK_DIR/pg.jar")
TXZ=$(ls "$WORK_DIR/bundle"/postgres-darwin*.txz | head -1)
(cd "$WORK_DIR/bundle" && tar xf "$TXZ")

PG_HOME="$WORK_DIR/bundle"
echo "    二进制解压完成：$(du -sh "$PG_HOME" | cut -f1)"
# Zonky 版本号（如 18.6.0）带构建后缀；源码版本以二进制自报为准（如 18.6），两者必须一致
PG_SOURCE_VERSION=$("$PG_HOME/bin/postgres" --version | grep -oE '[0-9]+\.[0-9]+')
echo "    源码版本对齐：$PG_SOURCE_VERSION"

# ---------------------------------------------------------------- 2. 源码树与 pgvector
SRC="$WORK_DIR/src/postgresql-$PG_SOURCE_VERSION"
fetch "$WORK_DIR/pg-src.tar.gz" \
  "https://mirrors.aliyun.com/postgresql/source/v$PG_SOURCE_VERSION/postgresql-$PG_SOURCE_VERSION.tar.gz" \
  "https://ftp.postgresql.org/pub/source/v$PG_SOURCE_VERSION/postgresql-$PG_SOURCE_VERSION.tar.gz"
mkdir -p "$WORK_DIR/src" && tar xzf "$WORK_DIR/pg-src.tar.gz" -C "$WORK_DIR/src"
echo "    配置源码树（仅生成 PGXS 与头文件）"
(cd "$SRC" && ./configure --without-readline --without-zlib --without-icu --without-openssl \
  --without-lz4 --without-zstd --disable-nls > "$WORK_DIR/configure.log" 2>&1)
# 构建期派生头（阶段 A 结论）
make -C "$SRC/src/include/catalog" > /dev/null 2>&1
make -C "$SRC/src/backend/nodes" node-support-stamp > /dev/null 2>&1
mkdir -p "$SRC/src/include/utils"
perl "$SRC/src/backend/utils/generate-errcodes.pl" "$SRC/src/backend/utils/errcodes.txt" \
  > "$SRC/src/include/utils/errcodes.h"

cat > "$SRC/src/makefiles/Makefile.pgxs" <<PGXS
# 树内 pgxs 入口（pgxs.mk 从该文件路径反推源码树根）
include $SRC/src/makefiles/pgxs.mk
PGXS
cat > "$WORK_DIR/pg-config" <<PGCONF
#!/bin/sh
case "\$1" in
  --configuration) echo "--prefix=$PG_HOME" ;;
  --version) echo "PostgreSQL $PG_VERSION" ;;
  --bindir) echo "$PG_HOME/bin" ;;
  --includedir|--includedir-server|--includedir-user) echo "$SRC/src/include" ;;
  --libdir) echo "$PG_HOME/lib" ;;
  --pkglibdir) echo "$PG_HOME/lib/postgresql" ;;
  --sharedir) echo "$PG_HOME/share/postgresql" ;;
  --htmldir|--docdir) echo "$PG_HOME/share/doc/postgresql" ;;
  --mandir) echo "$PG_HOME/share/man" ;;
  --pgxs) echo "$SRC/src/makefiles/Makefile.pgxs" ;;
  --cppflags) echo "" ;;
  --cflags) echo "-O2" ;;
  --ldflags|--ldflags_ex|--cflags_sl) echo "" ;;
  --cc) echo "clang" ;;
  *) echo "unknown option: \$1" >&2; exit 1 ;;
esac
PGCONF
chmod +x "$WORK_DIR/pg-config"

fetch "$WORK_DIR/pgvector.tar.gz" \
  "https://github.com/pgvector/pgvector/archive/refs/tags/v$PGVECTOR_VERSION.tar.gz" \
  "https://codeload.github.com/pgvector/pgvector/tar.gz/refs/tags/v$PGVECTOR_VERSION"
mkdir -p "$WORK_DIR/ext" && tar xzf "$WORK_DIR/pgvector.tar.gz" -C "$WORK_DIR/ext"
echo "    编译 pgvector"
(cd "$WORK_DIR/ext/pgvector-$PGVECTOR_VERSION" \
  && make clean > /dev/null 2>&1 || true \
  && make PG_CONFIG="$WORK_DIR/pg-config" PG_CPPFLAGS="-I$SRC/src/include" > "$WORK_DIR/pgvector.log" 2>&1)

# ---------------------------------------------------------------- 3. 安装扩展
mkdir -p "$PG_HOME/share/postgresql/extension"
SHLIB=$(ls "$WORK_DIR/ext/pgvector-$PGVECTOR_VERSION/"*.dylib | head -1)
cp "$SHLIB" "$PG_HOME/lib/postgresql/vector.dylib"
cp "$WORK_DIR/ext/pgvector-$PGVECTOR_VERSION/vector.control" "$PG_HOME/share/postgresql/extension/"
cp "$WORK_DIR/ext/pgvector-$PGVECTOR_VERSION/sql/vector--$PGVECTOR_VERSION.sql" "$PG_HOME/share/postgresql/extension/"

# ---------------------------------------------------------------- 4. 去重与输出
rm -rf "$OUTPUT_DIR"
# 完全相同的 dylib（如三份 libicudata）用符号链接去重
python3 - "$PG_HOME" <<'PY'
import hashlib, os, sys
root = sys.argv[1]
seen = {}
for dirpath, _, files in os.walk(os.path.join(root, 'lib')):
    for name in files:
        path = os.path.join(dirpath, name)
        if not name.endswith('.dylib') or os.path.islink(path):
            continue
        digest = hashlib.md5(open(path, 'rb').read()).hexdigest()
        if digest in seen:
            os.remove(path)
            # 相对符号链接：捆绑目录整体移动后仍然有效
            os.symlink(os.path.relpath(seen[digest], dirpath), path)
        else:
            seen[digest] = path
PY
mkdir -p "$OUTPUT_DIR"
cp -R "$PG_HOME/bin" "$PG_HOME/lib" "$PG_HOME/share" "$OUTPUT_DIR/"
echo "==> 完成：$(du -sh "$OUTPUT_DIR" | cut -f1)"
