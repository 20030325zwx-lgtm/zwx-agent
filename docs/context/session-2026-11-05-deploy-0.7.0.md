# 部署 0.7.0（工作智能体整改 A0-A2 后端）

## 部署内容

- master 提交：`10b23d1`（A0 记忆/授权隔离）、`5fd51ee`（A1 底座表结构）、`beebca0`（A2 持久化运行底座+派发+新接口）、`4a3b3b4`（A2 收尾 invocation 身份）、`e02db57`（进度摘要）。
- 镜像：仅 `aster-backend:0.7.0`（本机构建 linux/amd64，229MB，md5 双端校验一致）。
- 前端无代码改动：沿用 0.6.0 镜像，服务器重打 `aster-frontend:0.7.0` tag；其余未变服务（eval/data/image-mcp/document-mcp）同样重打 tag，未重启。
- 服务器 `/opt/aster`，公网 `https://www.zhuwenxuan.online`，源站 `1.13.152.215`。

## 流程与备份

- 本地服务已重启（launchctl backend/frontend），健康检查通过。
- 服务器备份：DB `/opt/aster/backups/backup-2026-11-05-0908.sql`（约 1.5MB）；`.env.bak-2026-11-05-0908`、`docker-compose.yml.bak-2026-11-05-0908`。
- `docker load` → 重打未变服务 tag → `IMAGE_TAG=0.7.0` → `docker compose up -d --no-deps backend`。

## 验证结果

- Flyway：**"Schema is up to date. No migration necessary"**——V34-V40 已在共享库中（本地开发后端经 SSH 隧道直连该生产库，本地重启时已执行）；本部署的迁移为幂等空操作。
- 容器：backend 0.7.0 healthy，其余服务未动。
- 内网 `/api/health` ok；公网 `GET /api/health` ok；首页 200；主资产 `assets/index-TPSiK0zw.js` 与 0.6.0 一致（前端未变）。
- 双层 nginx 已重载：`docker exec aster-frontend-1 nginx -s reload`（后端 IP 缓存）+ `docker exec aster-nginx-proxy nginx -s reload`（外层反代）。

## 0.7.0 行为说明（重要）

- 新增 v2 持久化运行接口（POST runs/snapshot/events/pause/cancel/resume/answer）为**并行入口**：旧聊天链路（GET /manus/chat，engine=1）保持不变，前端尚未迁移，线上不会产生 engine_version=2 的 run，`AgentRunDispatcher` 处于空转状态。
- A0 记忆隔离在线上生效：注入按 userId 过滤，无主事实不再落库/注入；授权决定持久化失败将返回错误而非内存放行。
- 请求确认粒度仍是 (run, tool_name)；operation_key 粒度与 V33 约束删除属后续批次。

## 回滚

```sh
cd /opt/aster
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.6.0/' .env
docker compose up -d --no-deps backend
docker exec aster-frontend-1 nginx -s reload
docker exec aster-nginx-proxy nginx -s reload
```

- 0.6.0 backend 镜像仍在服务器；V34-V40 为纯扩展式（加列/加表/加索引），回滚代码无需回滚数据。
- 如需严格回滚数据，使用 `backups/backup-2026-11-05-0908.sql`。

## 注意事项

- **本地开发后端连的就是生产库**（SSH 隧道 15432）：本地重启即生产迁移，破坏性迁移必须先在备份库演练，本批已遵守。
- 上传的 `/tmp/aster-0.7.0-backend.tar.gz` 双端已清理；未在文档记录任何密钥。
