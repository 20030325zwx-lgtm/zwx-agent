# 服务器部署 0.2.0 会话（2026-11-03）

## 改动内容

按 [DEPLOYMENT.md](../DEPLOYMENT.md) §7 更新流程，把 D1/D2/D3 + Skill 全部成果部署到腾讯云服务器：

- 服务器现状：`/opt/aster`（compose 项目 `aster`）原跑 0.1.0；本地开发库经 SSH 隧道 15432 指向的正是该机 postgres（同库）。
- 本地构建（不走完整 package.sh，最小化 5Mbps 上传）：`mvn package` + 复用最新 `frontend/dist`，构建 `aster-backend:0.2.0` / `aster-frontend:0.2.0`（linux/amd64），`docker save | gzip` 共 281MB。
- 服务器操作：DB 备份（`/opt/aster/backups/backup-2026-11-03-0125.sql`，1.2MB）→ `docker load` 两镜像 → 未变更的 image-mcp/document-mcp 本地重打 tag 0.1.0→0.2.0（同代码位）→ `.env` 备份后 `IMAGE_TAG=0.2.0` → `docker compose up -d backend frontend`。

## 验证结果

- backend/frontend 均 0.2.0 且 healthy；`/api/health` OK；首页 200。
- Flyway 最新 22（creative skill import run jobs）success=t；V21/V22 表存在。
- `creative_skill` 10 行（9 系统 + 1 测试导入），服务器后端启动种子正常。
- 注意：`SELECT max(version)` 对 varchar 版本列会得到字典序 '9'，需按 installed_rank 查。

## 域名切换（2026-11-03 追加）

- 域名 **https://www.zhuwenxuan.online** 已可用：CDN（解析 28.0.1.205/206）→ 源站 1.13.152.215，Nginx 代理（`/opt/aster/nginx/nginx.conf`）80→443 跳转、ACME webroot、`/resume/` → resume 站。证书 SAN 仅 `www.zhuwenxuan.online`，有效期至 2027-01-25。
- 已把 `/opt/aster/.env` 的 `PUBLIC_BASE_URL` 从 `http://1.13.152.215:8080` 改为 `https://www.zhuwenxuan.online`（该变量供 browser-mcp 等使用，browser-mcp 未启用）。
- 实测：`https://www.zhuwenxuan.online/` 200、`/api/health` 200、登录页正常；线上 `index-BIghRQYm.js` 与本地 dist 一致（无 CDN 旧缓存）。
- 顶点域 `zhuwenxuan.online` 无 A 记录（未解析）；如需裸域需在 DNS/CDN 侧添加并补 Nginx server 块/证书 SAN。
- 待办：证书续期方式未在服务器上找到 certbot 二进制，**需确认现有证书的签发/续期渠道**，避免 2027-01-25 过期；CDN 对 SSE（`/jobs/{id}/events/stream`）是否缓冲未验证，若不透传需依赖轮询事件接口。

## 遗留/注意事项

- **双 worker**：服务器后端与本地开发后端共用同一库，都会消费 media job。版本已统一（旧 worker 问题消除），但本地跑开发服务时任务会被两边抢；长期应给本地开发配置独立库或禁用调度。
- 服务器登录密码出现在截图中，建议尽快在腾讯云控制台重置；`.env`/`globe.conf` 保持 600。
- 访问地址：http://1.13.152.215:8080（无域名未备案，走 IP:8080）。
- 回滚：`.env.bak-0.1.0` + 0.1.0 镜像仍在服务器上；DB 备份已做。
