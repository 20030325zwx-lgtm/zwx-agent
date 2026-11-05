# 部署 0.7.1（账号菜单 / 记住密码 / 管理员用户管理）

## 部署内容

- master 提交：`3202e92`（全局导航账号菜单、登录记住密码、/users 用户管理、GET/DELETE /auth/users、开户支持 USER/ADMIN、AuthControllerTest 10 项）。
- 镜像：`aster-backend:0.7.1` + `aster-frontend:0.7.1`（本机构建 linux/amd64，299MB，md5 双端校验一致）。
- **无数据库迁移**：本次未执行 pg_dump（无 schema 变更风险）；仅备份 `.env`/`docker-compose.yml`（`*.bak-2026-11-05-1012`）。
- 未变服务（eval/data/image-mcp/document-mcp）服务器重打 0.7.1 tag，未重启。

## 流程

- 本地：`mvn -DskipTests package` + 前端 dist 重建；launchctl 重启 backend/frontend；health ok、登录接口验证正常。
- 服务器：`docker load` → 未变服务重打 tag → `IMAGE_TAG=0.7.1` → `docker compose up -d --no-deps backend frontend`。

## 验证结果

- 容器：backend 0.7.1 healthy、frontend 0.7.1 Up。
- 内网 `/api/health` ok；公网 `GET /api/health` ok；首页 200。
- 线上主资产 `assets/index-DxAdPlw6.js` 与本机 `frontend/dist/index.html` 一致（新前端生效）。
- 双层 nginx 已重载：`aster-frontend-1`（清后端 IP 缓存）+ `aster-nginx-proxy`（外层反代）。

## 回滚

```sh
cd /opt/aster
sed -i 's/^IMAGE_TAG=.*/IMAGE_TAG=0.7.0/' .env
docker compose up -d --no-deps backend frontend
docker exec aster-frontend-1 nginx -s reload
docker exec aster-nginx-proxy nginx -s reload
```

- 0.7.0 镜像仍在服务器；无数据库变更，回滚无需恢复数据。

## 注意事项

- 新用户管理接口仅 ADMIN 可用；禁止删除自己与最后一个管理员、跨租户 404（已由提交内测试覆盖）。
- 记住密码为本机 Base64 存储（非加密），仅限本机开发/使用预期；如需更高安全等级另行改造。
- 上传的 `/tmp/aster-0.7.1-images.tar.gz` 双端已清理。
