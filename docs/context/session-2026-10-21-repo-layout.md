# 会话摘要：仓库顶层目录整理（2026-10-21）

## 改动内容

- 前端 `zwx-agent-frontend` -> `frontend`。
- 桌面端 `zwx-agent-desktop` -> `desktop`。
- 图片 MCP `zwx-image-search-mcp-server` -> `services/image-search-mcp`。
- 文档统一到 `docs/`：
  - `design/` -> `docs/design/`
  - `context/` -> `docs/context/`
  - `doc/*` -> `docs/*`
- 部署相关集中到 `deploy/`：
  - `Dockerfile` -> `deploy/Dockerfile.backend`
  - `docker-compose/` -> `deploy/docker-compose/`
  - `scripts/` -> `deploy/scripts/`
- 后端 `src/`、`pom.xml`、`target/` 和 `src/main/resources/application-local.yml` 路径保持不变。

## 同步更新

- README、AGENTS 的前端路径、文档链接、部署脚本路径。
- `deploy/scripts/package.sh` 使用新的根目录、frontend 与 Dockerfile 路径。
- `.dockerignore`、`.gitignore` 中的前端/MCP 路径。
- desktop 的前端构建路径。
- 前端 launchctl 常驻任务已切换到 `frontend` 目录并重启。

## 验证结果

- `sh -n deploy/scripts/package.sh`、`install.sh`、`stop.sh` 通过。
- 前端 `npm run build` 通过，`http://127.0.0.1:3000/` 返回 200。
- 全量 `mvn test`：198 个测试，0 失败，0 错误，1 跳过。
- 后端 `/api/health` 返回 `ok`。

## 遗留事项

- 根目录仍保留 `target/`、`temp/`、`data/`、`skills/`，分别是构建产物、运行时工作区、示例语料和运行时 Skill 配置。
- 本轮改动尚未提交。
