# Aster（星枢）服务器部署手册（4核8G / Docker）

本文档记录从构建机打包到服务器部署、验证、更新和回滚的完整流程。目标是：服务器只运行镜像，不在服务器上执行 Maven/npm/浏览器构建。

## 1. 部署拓扑

默认部署（4核8G 推荐）：

```text
浏览器
  |
  v
frontend (Nginx, 8080/80)
  |-- /api/ ----------> backend:8123
  |-- /screenshots/ --> browser-mcp:8129（可选）
  |
  +-- backend
        |-- postgres:5432
        |-- image-mcp:8127
        |-- document-mcp:8128
        |-- MinerU 在线 API（知识库入库、parse_document）
        |-- DashScope / 阿里云 OSS

独立中台服务（0.4.0 起，各自独立镜像与页面，与主站同仓库）：
  +-- eval-service  评测中台镜像：/eval/ 页面 + /api/ai/eval/**；实验调度在本服务内执行
  +-- data-service  数据中台镜像：/data/ 页面 + /api/ai/feedback/**；回流调 eval-service
        两者共用同一个 postgres（platform schema，主站是 schema 所有者）；
        执行链路经主站内部 API（/api/internal/eval/**，X-Aster-Internal-Token 鉴权）。
```

- `browser-mcp` 默认不启用，使用 Docker Compose `browser` profile；需要网页截图时再打开。
- Docling 默认不部署，知识库 PDF/Office 入库使用 MinerU；Docling 仅作为私有化部署选项。
- MinIO 默认不部署，图片和知识库文档使用阿里云 OSS。
- 两个中台服务只做编排/评分/反馈管理，不重复实现智能体运行时：TASK/意图/检索经主站
  `/api/internal/eval/**` 执行（共享令牌 `PLATFORM_INTERNAL_TOKEN`，fail-closed）。
- 内存：eval-service 1024m、data-service 512m；中台数据在 `platform` schema，
  V23/V24 迁移仍由 backend 负责（部署顺序 backend 先启动即完成迁移）。

## 2. 服务器前置条件

- 4核8G、60GB SSD、5M 带宽（当前购买规格）
- Ubuntu 22.04/24.04 或兼容 Linux
- Docker Engine + Docker Compose v2
- 服务器安全组只开放：SSH、80、443
- 一个可访问的域名（中国大陆服务器需完成 ICP 备案；没有域名可先用 IP:8080）
- 阿里云 OSS Bucket 与 AccessKey（图片、知识库文档）
- DashScope API Key
- MinerU Token
- 可选：HTTPS 证书（推荐用 Caddy / Nginx + certbot 自动签发）

## 3. 构建机打包

构建机需要 JDK 21、Maven、Node.js、Docker。

```bash
VERSION=0.1.0 TARGET_PLATFORM=linux/amd64 sh deploy/scripts/package.sh
```

产物：

```text
release/aster-0.1.0-linux-amd64/
├── images/
│   ├── aster-backend.tar
│   ├── aster-frontend.tar
│   ├── aster-image-mcp.tar
│   ├── aster-document-mcp.tar
│   ├── pgvector-pg16.tar
│   └── aster-browser-mcp.tar（可选）
├── docker-compose.yml
├── .env.example
├── globe.conf.example
├── install.sh
└── stop.sh
release/aster-0.1.0-linux-amd64.tar.gz
```

需要网页截图时：

```bash
VERSION=0.1.0 TARGET_PLATFORM=linux/amd64 WITH_BROWSER_MCP=true \
  sh deploy/scripts/package.sh
```

> Playwright 浏览器镜像较大，构建时间也较长。默认不构建；不启用截图功能时无需等待。

## 4. 服务器配置

把安装包上传到服务器并解压：

```bash
tar -xzf aster-0.1.0-linux-amd64.tar.gz
cd aster-0.1.0-linux-amd64
```

### 4.1 `.env`

```bash
cp .env.example .env
chmod 600 .env
```

至少填写：

```env
IMAGE_TAG=0.1.0
ASTER_PORT=8080
POSTGRES_PASSWORD=<随机强密码>
DASHSCOPE_API_KEY=<DashScope Key>
MINERU_TOKEN=<MinerU Token>
PUBLIC_BASE_URL=http://<服务器IP或域名>:8080
ALIYUN_OSS_ENDPOINT=https://oss-cn-hangzhou.aliyuncs.com
ALIYUN_OSS_BUCKET=<Bucket>
ALIYUN_OSS_ACCESS_KEY_ID=<AccessKeyId>
ALIYUN_OSS_ACCESS_KEY_SECRET=<AccessKeySecret>
```

可选：

```env
BROWSER_MCP_ENABLED=true
BROWSER_ALLOWED_HOSTS=*
```

开启截图时必须同时用 `WITH_BROWSER_MCP=true` 打包，并确保 `PUBLIC_BASE_URL` 是浏览器可访问的地址。

### 4.2 `globe.conf`

```bash
cp globe.conf.example /home/globe.conf
chmod 600 /home/globe.conf
```

至少填写：

```properties
app.security.jwt-secret=<至少32位随机字符串>
app.security.admin-password=<管理员初始密码>
app.config.encryption-key=<建议单独设置>
```

`app.security.jwt-secret` 是必填项；管理员密码建议在首次部署前设置，避免使用默认 `admin123`。

> **与本地共享数据库时必须统一 `app.config.encryption-key`**：供应商密钥、模型覆盖密钥和 MCP 密钥
> 都以该口令加密后存库（`*.secret_ciphertext`）。服务器写在 `/home/globe.conf`，
> 本地写在 `src/main/resources/application-local.yml` 的 `app.config.encryption-key`
> （该文件 Git 忽略），两边必须是同一个值。只改密钥而不重新加密存量数据，会导致
> `model_provider` / `app_configuration` / `mcp_server_configuration` 里的密文无法解密。
>
> 更换口令的正确顺序：停两端后端 → 备份数据库 → 用旧口令解密、新口令重加密全部
> `secret_ciphertext` → 同步更新两端配置 → 启动并验证技能/模型列表不再出现
> 「模型绑定缺少可解密密钥，回退下一层」。后端对密钥不可用的绑定有回退保护，
> 但回退只是兜底，不能替代两端口令一致。

### 4.3 启动

```bash
./install.sh
```

`install.sh` 会：

1. 校验 `.env`、`globe.conf` 和必填密钥；
2. 加载本地镜像；
3. 启动 PostgreSQL、后端、前端、图片 MCP、文档 MCP；
4. 如果 `BROWSER_MCP_ENABLED=true`，额外启动 browser-mcp；
5. 等待 PostgreSQL 和后端健康检查；
6. 自动注册默认 MCP 服务：
   - 图片理解 `http://image-mcp:8127`
   - 文档解析 `http://document-mcp:8128`
   - 网页截图 `http://browser-mcp:8129`（仅启用时）

访问：

```text
http://<服务器IP>:8080
```

## 5. 部署后验证

```bash
# 容器状态
docker compose ps

# 后端健康检查
curl -fsS http://127.0.0.1:8080/api/health

# 查看日志
docker compose logs -f backend
docker compose logs -f document-mcp
```

页面验证：

1. 登录管理员账号；
2. `/model-settings` 确认模型配置正常；
3. `/knowledge-admin` 上传一个 PDF，确认状态 `READY`；
4. 检查切片元数据 `parser=mineru`；
5. 超级智能体上传图片，确认图片理解正常；
6. 开启截图 MCP 后，让智能体截图一个公开网页，确认 `/screenshots/...` 可访问。

数据库抽查：

```bash
docker compose exec -T postgres psql -U zwx_agent -d zwx_agent -c \
  "select metadata->>'parser', count(*) from agent_knowledge_vector group by 1;"
```

## 6. HTTPS 与域名

推荐在服务器上使用 Caddy 或 Nginx 反向代理到 `127.0.0.1:8080`，只对外暴露 80/443。

Caddy 示例：

```caddyfile
agent.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

如果使用域名：

- 将 DNS A 记录指向服务器；
- 中国大陆服务器先完成 ICP 备案；
- 把 `.env` 的 `PUBLIC_BASE_URL` 改为 `https://agent.example.com`；
- 重新执行 `./install.sh` 或重启 frontend/browser-mcp。

## 7. 更新与回滚

### 更新

```bash
# 构建机
VERSION=0.1.1 sh deploy/scripts/package.sh

# 服务器：解压新版本，保留旧目录
cd aster-0.1.1-linux-amd64
cp ../aster-0.1.0-linux-amd64/.env .env
cp ../aster-0.1.0-linux-amd64/../globe.conf /home/globe.conf  # 如路径不同请调整
./install.sh
```

Flyway 会在后端启动时自动执行数据库迁移。

> **重建 frontend 后必须重载外层 nginx**：`aster-nginx-proxy`（独立容器，反代 `frontend:80`）在启动时解析容器 IP；
> `docker compose up -d` 重建 frontend 后 IP 变化会导致 502。执行
> `docker exec aster-nginx-proxy nginx -s reload` 即可（不会影响同一反代下的其它站点）。


### 独立中台服务（评测 / 数据中台）

`deploy/docker-compose/docker-compose.yml` 含 `eval-service`（8140）与 `data-service`（8141），
各自独立镜像（`aster-eval-service` / `aster-data-service`），不对外暴露端口：

- nginx 前缀路由（`frontend/nginx.conf`）：`/api/ai/eval/`、`/eval/` → eval-service；
  `/api/ai/feedback`、`/data/` → data-service。
- 环境变量：`PLATFORM_INTERNAL_TOKEN`（服务间令牌，必填）、`JWT_SECRET`（与主站
  globe.conf 的 `app.security.jwt-secret` 必须一致，中台服务本地验签用）、`DASHSCOPE_API_KEY`
  （PROMPT/judge 直连）、`EVAL_MODEL`（默认 qwen-plus）。
- **CI 只读门禁**（可选）：`.env` 设 `PLATFORM_CI_TOKEN`（留空禁用）、`PLATFORM_CI_TENANT`
  （默认 default）。该令牌只能 `GET /api/ai/eval/experiments**`（gate/report/export/结果），
  写操作与其它路径一律 403，适合给发布脚本消费。
- 数据中台「运行轨迹」走主站内部只读端点（`/api/internal/eval/runs**`），
  依赖 `PLATFORM_INTERNAL_TOKEN` 与主站 `ASTER_BACKEND_URL`。
- 部署脚本（release/deploy-0.4.0.sh）会自动从 globe.conf 提取 JWT_SECRET、随机生成
  PLATFORM_INTERNAL_TOKEN 并写入 `.env`。
- **重建 backend/frontend 容器后必须重启 nginx-proxy**（DNS 缓存，否则 502）。

### 回滚

1. 停止新版本；
2. 恢复上一版 `docker-compose.yml`、`.env` 和镜像；
3. 如果迁移不可逆，恢复数据库备份；
4. 重新启动。

建议每次发布前备份：

```bash
docker compose exec -T postgres pg_dump -U zwx_agent -d zwx_agent > backup-$(date +%F-%H%M).sql
```

## 8. 从本地迁移数据（可选）

如果要把本地已有的会话、知识库文档和向量迁移到服务器：

```bash
# 本地
docker exec yu-ai-agent-postgres pg_dump -U zwx_agent -d zwx_agent > zwx_agent-local.sql

# 上传到服务器后
docker compose exec -T postgres psql -U zwx_agent -d zwx_agent < zwx_agent-local.sql
```

注意：

- 知识库文件对象仍在本地 OSS/MinIO 中，换 Bucket 时需要同步对象；
- `application-local.yml` 不迁移、不提交；
- 如果只迁移知识文档，不上传历史会话，可以只导出相关表。

## 9. 运维注意事项

- 只开放 22/80/443；PostgreSQL、MCP、后端端口只在 Docker 内网。
- `.env` 和 `/home/globe.conf` 权限保持 600，不进入 Git。
- 截图目录设置清理周期；浏览器 MCP 限制单并发。
- 5M 带宽上传大 PDF 会较慢；建议后续做 OSS 直传。
- 60GB 磁盘建议监控 Docker 镜像、日志、截图和数据库备份占用。
- MinerU 会将文档发送到第三方，敏感资料需确认合规；可切回私有 Docling。
- MinerU Token 曾在对话中明文出现，建议部署前轮换。

## 10. 我需要你提供的部署信息

- 服务器 SSH 地址、用户名、登录方式（密钥/密码）
- 服务器操作系统版本
- 域名与是否已备案（如使用 HTTPS）
- 是否迁移本地数据（会话、知识库）
- 是否启用网页截图 MCP
- OSS Bucket/AccessKey、DashScope Key、MinerU Token（不要发到公开频道，部署时通过安全方式提供）

## 11. MinIO（自建对象存储，2026-11-04 部署 / 2026-11-05 接入应用）

- **部署形态**：systemd 服务（非 Docker），二进制 `RELEASE.2025-04-22T22-12-26Z`（GitHub Releases 下载，sha256 已校验；dl.min.io 已下架社区二进制）。
- **服务**：`systemctl status minio`；数据目录 `/opt/aster/minio-data`；API `:9000`（0.0.0.0，防火墙默认拦截）、Console `127.0.0.1:9001`。
- **凭据**：`/opt/aster/minio.env`（root 600）；应用侧配置已追加到 `/opt/aster/.env`（MINIO_ENDPOINT=http://172.17.0.1:9000、MINIO_BUCKET=aster-media）。
- **桶**：`aster-media` 已创建（mc 客户端 /usr/local/bin/mc，alias=aster）。
- **nginx**：已加 9443 ssl server 块（同证书 www.zhuwenxuan.online，Host 透传，client_max_body_size 0）→ 172.17.0.1:9000；配置备份 nginx.conf.bak-minio。
- **应用接入（2026-11-05）**：对象存储统一抽象 `platform/storage`，`STORAGE_PROVIDER=s3` 时
  创作素材 / 知识库文档与图片 / 工作流产物 / 对话图片全部写入 MinIO；`STORAGE_FALLBACK_PROVIDER=oss`
  在迁移期保证历史对象仍可读。历史对象已用 `app.storage.migrate=enabled` 全量镜像（313 个，key 不变）。
  详见 `docs/design/2026-11-05-storage-minio-migration.md`。
- **不再需要公网入口**：原先 4 条把预签名 URL 交给外部的链路已改为字节/base64/同源签名下载，
  所以 minio 只在内网（`172.17.0.1:9000`）即可；`MINIO_PUBLIC_ENDPOINT` 保持留空。
  nginx 的 9443 server 块可作为未来直链方案保留（当前未对外放通）。
- **运维**：备份数据目录即备份存储；磁盘占用需监控（当前 38G 可用）。
