# 会话摘要：品牌更名为 Aster（星枢）（2026-10-22）

## 决策

- 对外品牌名：**Aster**，中文名：**星枢**。
- 本次范围：品牌展示 + 构建产物；Java 包名、数据库名、用户/主题 localStorage 键保持不变，降低回归风险。

## 改动内容

### 品牌展示

- 首页、登录页、工作区顶栏、页脚和路由标题从 `ZWX Agent` 改为 `Aster`。
- 品牌标记从 `Z` 改为 `A`。
- 桌面端 `productName` 改为 `Aster`，窗口标题改为 `Aster`。
- README、ROADMAP、架构文档和部署手册更新为 Aster。

### 构建产物

- Maven 主 artifact：`zwx-agent` -> `aster-agent`，JAR 变为 `aster-agent-0.0.1-SNAPSHOT.jar`。
- 图片 MCP artifact：`aster-image-mcp-server`。
- 文档 MCP artifact：`aster-document-mcp-server`。
- 浏览器 MCP 包：`aster-browser-mcp-server`。
- Docker 镜像：`aster-backend`、`aster-frontend`、`aster-image-mcp`、`aster-document-mcp`、`aster-browser-mcp`。
- Compose 项目：`aster`。
- 安装包：`aster-<version>-<platform>.tar.gz`。
- 安装目录：`/opt/aster`。
- 端口变量：`ZWX_AGENT_PORT` -> `ASTER_PORT`。
- 图片/文档 MCP Dockerfile、application.yml、README、Docker Compose 名称同步更新。

### 兼容性保留

- Java 包名 `com.zwx.zwxagent` 未改。
- 数据库名/用户名 `zwx_agent` 未改。
- 前端 localStorage key `zwx_auth_token`、`zwx_auth_user`、`zwx-agent-theme:*` 未改。
- `window.zwxDesktop` 桌面桥接对象未改。
- `com.zwx.yu-ai-agent.*` launchctl 标签未改。

## 验证结果

- 主应用 `mvn clean package` 成功；测试 199 个，0 失败，0 错误，1 跳过。
- 前端 `npm run build` 成功；页面标题为 `Aster`。
- 图片 MCP、文档 MCP 重新构建成功，并以 Aster 名称启动/健康检查通过。
- 主应用、document-mcp、image-mcp 健康检查均返回 `ok`。
- `sh -n` 脚本检查通过；`docker compose config` 默认和 `--profile browser` 均通过。

## 遗留事项

- 桌面端安装包尚未重新打出，需要时运行 `npm run dist:mac`。
- 旧的历史文档和 `docs/context` 中仍保留 `ZWX Agent`，仅作历史记录，不影响运行。
- 旧镜像/旧容器可能仍在本地 Docker 中，可在确认无误后清理。
- 本轮改动尚未提交。
