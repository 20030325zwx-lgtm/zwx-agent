# 会话摘要：2026-11-04 部署 0.4.0（DeepSeek 对话页）与并发覆盖记录

## 本次做的事

1. 用 **pristine master `60b35ef`**（`git archive` 到 /tmp，不含并行会话未提交的 eval 平台代码）构建并部署：
   - `aster-backend:0.4.0`（linux/amd64，440M）、`aster-frontend:0.4.0`（130M）；
   - 上传压缩包 → 服务器 `docker load` → 备份 `/opt/aster/docker-compose.yml`、`.env`（`*.bak-2026-11-04`）→ 更新为 master 版 compose（新增 `PEXELS_API_KEY`、`APP_PROJECT_WORKSPACE_ROOT` + `./projects` 卷）→ `IMAGE_TAG=0.4.0` → `docker compose up -d`。
2. 验证（09:38）：五容器 healthy、`/api/health=ok`、本地 8080 与域名均 200，CDN 返回 `assets/index-BCryKnnI.js` 与本机构建哈希一致。
3. **1 分钟后（09:39）并行会话部署了 `0.3.2`**，覆盖了 `/opt/aster/docker-compose.yml` 与 `.env`（`IMAGE_TAG=0.3.2`），并新增 `eval-worker`/`data-worker` 容器（eval 平台尚未合入 master）。
4. 侦察确认：线上 `0.3.2` 前端 **已包含本次 DeepSeek 对话页样式**（容器内 `SuperAgent-*.css` 含 `pill-label`、JS 含 `pill-toggle`），全部容器 healthy、域名 200，故不回滚。
5. 兼容修正：并行会话 compose 引用 `aster-image-mcp:0.3.2` / `aster-document-mcp:0.3.2` 但这两个 tag 不存在，补齐为 `0.4.0`（同一镜像）的别名，避免他们下次 `up -d` 失败。

## 当前服务器状态

- 运行：backend/frontend/eval-worker/data-worker = `0.3.2`；image-mcp/document-mcp 实为 `0.4.0` tag（等同 0.3.2 镜像）；postgres/pgvector 不变。
- 保留镜像：`aster-backend|frontend: 0.3.1（回滚）、0.3.2（当前）`；`mcp: 0.3.1(回滚)、0.3.2、0.4.0（同一 ID）`。
- 备份：`/opt/aster/docker-compose.yml.bak-2026-11-04`、`/opt/aster/.env.bak-2026-11-04`。
- 清理：上传 tar 包、`/tmp/compose-master.yml`、`aster-backend|frontend:0.4.0` 镜像、旧 0.1.0~0.3.0 镜像均删除，磁盘 19G→18G used；本机 `/tmp/aster-deploy-0.4.0` 与本地 0.4.0 镜像同步删除。

## 注意事项

- **不要在与并行会话同时段对同一服务器部署**：09:39 的覆盖导致 compose/env 已被替换为含 eval 平台（未合入 master）的版本；如需回到纯 master，用 `IMAGE_TAG=0.4.0` 重建镜像并恢复 `.bak-2026-11-04`。
- 服务器 `ubuntu` 用户已安装本机 SSH 公钥（免密可用）；root 密码仍在截图中暴露过，建议在腾讯云控制台重置。
- 服务器运行代码 ≠ master：eval 平台改动未提交，属于并行会话的临时部署状态。
