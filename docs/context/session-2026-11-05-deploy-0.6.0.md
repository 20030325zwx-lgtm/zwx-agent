# 部署 0.6.0（创作画布 P1 + 开始节点 + 实时调用链）

## 部署内容

- master 提交 `e1426b9`（智能体实时子任务调用链、V33）、`897c404`（画布执行到此节点/拖线双连/连线键盘删除）、`207a2c9`、`e67eed6`（文档）。
- 镜像：`aster-backend:0.6.0` + `aster-frontend:0.6.0`（本机构建 linux/amd64，295MB tar.gz 上传）。
- 未变服务（eval/data/image-mcp/document-mcp，仍是 0.5.0 镜像 ID）在服务器 `docker tag` 重打为 0.6.0，未重启。
- 服务器 `/opt/aster`，公网 `https://www.zhuwenxuan.online`，源站 `1.13.152.215`。

## 流程与备份

- 前端 dist 重新构建（删边功能在上一轮 dist 之后才提交，旧 dist 不含）；后端 JAR 沿用 00:19 package（已核对无更新的源码）。
- DB 备份：`/opt/aster/backups/backup-2026-11-05-0045.sql`（1.5MB）；`.env.bak-2026-11-05-0045`、`docker-compose.yml.bak-2026-11-05-0045`。
- 服务器 compose 与仓库 `deploy/docker-compose/docker-compose.yml` md5 一致（9db4f368…）。
- tar.gz md5 双端校验一致后 `docker load`。

## 验证结果

- Flyway：V31 creative canvas draft / V32 creative canvas runs / V33 agent tool worker key 全部 success。
- 容器：backend 0.6.0 healthy、frontend 0.6.0 Up，其余未动。
- 公网 `GET /api/health` 返回 ok；首页 200；线上主资产 `assets/index-TPSiK0zw.js` 与本机 dist 哈希一致。
- 未调用真实模型 API，未读取 `.env`/`globe.conf` 内容，未在文档记录任何密钥。

## 缓存清理（用户要求）

- `docker exec aster-nginx-proxy nginx -s reload`（重建 frontend 后必须，防 502；输出仅既有 http2 弃用告警）。
- 清理上传临时包：服务器与本地 `/tmp/aster-0.6.0-images.tar.gz` 均已删除。
- 未执行 `docker image prune`：0.5.x 镜像保留用于回滚。

## 回滚

```sh
cd /opt/aster
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.5.2/' .env
docker compose up -d --no-deps backend frontend
docker exec aster-nginx-proxy nginx -s reload
```

- V31–V33 均为新增表/列，回滚代码不需要回滚数据（如需严格回滚再恢复 backup-2026-11-05-0045.sql）。
- 0.5.1/0.5.2 镜像仍在服务器。

## 注意事项

- 画布新功能依赖模型费用，线上首次使用建议小额验收一次真实生成（本次部署未做付费 E2E）。
- 旧会话遗留：服务器 frontend 曾是 0.5.1（0.5.2 仅更新 backend），本轮对齐到 0.6.0。
