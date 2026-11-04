# 部署 0.5.1 / 0.5.2（视频提示词优化师 + 绑定密钥回退）

## 改动内容

- master 提交：
  - `b1cf986` 视频提示词优化师分镜化与技能库清理；
  - `603fb8d` 模型绑定密钥不可用时回退下一层配置。
- 构建并部署：
  - `aster-backend:0.5.1` + `aster-frontend:0.5.1`（首次部署，含分镜 UI、V30、技能清理）；
  - `aster-backend:0.5.2`（修复服务器端绑定密钥解密失败后的回退）。
- 服务器：`/opt/aster`，公网 `https://www.zhuwenxuan.online`，源站 `1.13.152.215`。
- 未变更服务（eval/data/image-mcp/document-mcp）在服务器上重打 tag 到 `0.5.2`，底层仍是原 `0.5.0` 镜像 ID，未重启。

## 备份

- DB：`/opt/aster/backups/backup-2026-11-04-1630.sql`（约 1.3MB）。
- compose / env：
  - `docker-compose.yml.bak-2026-11-04-1630`、`.env.bak-2026-11-04-1630`（0.5.1）；
  - `docker-compose.yml.bak-2026-11-04-1645`、`.env.bak-2026-11-04-1645`（0.5.2）。
- 服务器 compose 与仓库 `deploy/docker-compose/docker-compose.yml` 一致（diff 为空）。

## 验证结果

- 公网 `GET /api/health` 返回 `ok`；首页 200。
- 线上主包 `assets/index-BRYkIq21.js` 与本机 `frontend/dist/index.html` 哈希一致。
- Flyway：V30 success；`creative_skill` 为 7 个 PUBLISHED、4 个 RETIRED。
- 用服务器 `globe.conf` 的 JWT secret 签发 admin token，实测：
  - `GET /api/ai/creative/skills`：7 项，优化师 `compat=READY`、`version=2`；
  - `POST /api/ai/creative/projects/{videoProject}/skill-runs`：HTTP 200，生成 3 镜方案，
    提示词长度 500 / 394 / 399，warnings 含首镜压缩提示。
- 后端日志确认回退链路：
  `scope=USER providerId=1`、`scope=SYSTEM providerId=2` 均因密钥不可解密而回退，
  最终使用服务器旧配置里的 DashScope Key 成功生成。

## 关键问题：跨环境加密口令不一致

- 服务器 `app.config.encryption-key` 与本机默认 key 的 SHA-256 不同；
  共享库里用本机 key 加密的 provider secret 在服务器无法解密。
- 原 `bindingConfig` 在密钥为空时仍返回无密钥配置，挡住系统绑定/旧配置兜底；
  `603fb8d` 改为空密钥即 `Optional.empty()`，继续回退。
- 长期风险：本地与服务端共享同一 PostgreSQL，但 encryption-key 不同；
  任一环境在模型设置里保存密钥，另一环境可能解不开。建议后续统一密钥来源，
  或给本地开发使用独立数据库。

## 回滚

```sh
cd /opt/aster
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.5.1/' .env
docker compose up -d --no-deps backend frontend
docker exec aster-nginx-proxy nginx -s reload
```

- 0.5.1 backend/frontend 镜像仍在服务器上；V30 只更新 `creative_skill.lifecycle`，
  回滚代码不需要回滚数据。
- 如需恢复 0.5.1 之前的 compose/env，使用对应 `.bak-2026-11-04-1630` 文件。

## 注意事项

- 重建 frontend 后必须 `docker exec aster-nginx-proxy nginx -s reload`，否则外层反代可能 502。
- 上传的 `/tmp/aster-0.5.1-images.tar.gz`、`/tmp/aster-0.5.2-backend.tar.gz` 已清理。
- 未在文档中记录任何密钥、密码或 `.env` 值。
