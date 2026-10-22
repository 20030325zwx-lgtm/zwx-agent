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
```

- `browser-mcp` 默认不启用，使用 Docker Compose `browser` profile；需要网页截图时再打开。
- Docling 默认不部署，知识库 PDF/Office 入库使用 MinerU；Docling 仅作为私有化部署选项。
- MinIO 默认不部署，图片和知识库文档使用阿里云 OSS。

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
