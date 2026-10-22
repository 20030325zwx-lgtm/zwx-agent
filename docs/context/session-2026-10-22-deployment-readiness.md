# 会话摘要：4核8G 部署准备（2026-10-22）

## 改动内容

- 新增部署手册 `docs/DEPLOYMENT.md`，覆盖构建、服务器配置、启动、验证、更新、回滚、数据迁移和 HTTPS。
- 统一生产 compose（`deploy/docker-compose/docker-compose.yml`）：
  - postgres、backend、frontend；
  - image-mcp、document-mcp；
  - browser-mcp 放在 `browser` profile，默认不启动；
  - 内存上限：backend 1536m、postgres 1g、image-mcp/document-mcp 各 512m、browser-mcp 1536m、frontend 128m。
- 后端增加 `app.mcp.allow-private-endpoints`；生产 compose 开启，允许访问 `http://image-mcp:8127` 等服务名。
- Nginx 增加 `/screenshots/` 代理到 browser-mcp，并使用 Docker DNS 变量解析，browser-mcp 未启动时 Nginx 仍可启动。
- `deploy/scripts/package.sh`：
  - 使用系统 `mvn` 构建；
  - 构建 image/document MCP 镜像；
  - `WITH_BROWSER_MCP=true` 时才构建并打包 browser-mcp。
- `install.sh`：
  - 校验 DashScope、MinerU、PUBLIC_BASE_URL（启用截图时）；
  - 加载全部镜像；
  - 启动默认服务，按需启动 browser profile；
  - 自动注册图片理解、文档解析、网页截图 MCP 配置。
- 部署 `.env.example` 增加 `MINERU_TOKEN`、解析 provider、`BROWSER_MCP_ENABLED`、`PUBLIC_BASE_URL`。

## 验证结果

- 主应用测试：199 个，0 失败，0 错误，1 跳过。
- `sh -n` 校验 package/install/stop 脚本通过。
- `docker compose config` 默认配置和 `--profile browser` 配置均通过。
- image MCP 镜像已构建；document MCP Docker 镜像构建成功。
- browser MCP 的 Playwright 镜像构建在 15 分钟内未完成，因此默认改为可选，未纳入默认打包。
- MinerU 知识库入库已在此前验证：上传 PDF 后 READY、切片 metadata `parser=mineru`。

## 遗留事项

- 默认部署不启用 browser-mcp；需要时用 `WITH_BROWSER_MCP=true` 打包，并在服务器 `.env` 设置 `BROWSER_MCP_ENABLED=true` 和 `PUBLIC_BASE_URL`。
- 服务器 HTTPS、域名、ICP 备案和最终安全组配置需要在拿到服务器信息后执行。
- 是否迁移本地会话和知识库数据待确认；OSS 对象需要单独同步。
- 本轮改动尚未提交。

## 环境注意事项

- Token 仍只保存在 `services/document-mcp/.env`（Git 忽略），部署时通过服务器 `.env` 提供。
- 生产 compose 不再包含 Docling/MinIO；使用 MinerU + 阿里云 OSS。
- 服务器只应开放 SSH、80、443；MCP/数据库端口仅在 Docker 内网。
