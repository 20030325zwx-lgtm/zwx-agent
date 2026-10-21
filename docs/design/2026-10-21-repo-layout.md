# 仓库顶层目录整理（2026-10-21）

## 目标

减少根目录平铺的应用、文档与部署目录，让仓库按“后端 / 前端 / 桌面端 / 服务 / 部署 / 文档”分区，同时保持本地敏感配置路径不变。

## 当前结构

```text
yu-ai-agent/
├── src/                  # Spring Boot 后端（保持原路径）
├── pom.xml
├── mvnw / mvnw.cmd / .mvn
├── frontend/             # Vue 3 前端（原 zwx-agent-frontend）
├── desktop/              # Electron 桌面端（原 zwx-agent-desktop）
├── services/
│   └── image-search-mcp/ # 图片搜索/理解 MCP（原 zwx-image-search-mcp-server）
├── deploy/
│   ├── Dockerfile.backend
│   ├── docker-compose/   # 生产编排与安装脚本
│   └── scripts/          # 离线安装包构建脚本
├── docs/
│   ├── design/           # 稳定设计文档
│   ├── context/          # 会话交接摘要
│   ├── ROADMAP.md
│   └── ...
├── data/                 # 示例知识语料
├── skills/               # 运行时 Skill 文件
├── temp/                 # 运行时工作区（Git 忽略）
├── target/               # Maven 构建产物（Git 忽略）
├── README.md
└── AGENTS.md
```

## 保持不变

- `src/main/resources/application-local.yml` 路径和内容不变。
- 后端构建与启动命令仍在仓库根目录执行。
- `skills/` 仍作为 `app.skills.dir` 的默认相对路径。

## 同步更新

- `README.md`、`AGENTS.md` 中的前端路径、部署脚本路径和文档链接。
- `deploy/scripts/package.sh` 的根目录、前端、Dockerfile 与 compose 路径。
- `desktop` 的前端构建路径。
- `.dockerignore`、`.gitignore` 中的前端与 MCP 路径。
- 文档中的 `frontend`、`desktop`、`services/image-search-mcp`、`docs/...` 引用。

## 验证

- `sh -n` 校验部署脚本语法。
- 前端 `npm run build` 通过。
- 全量 `mvn test`：198 个测试，0 失败，0 错误，1 跳过。
- 后端 `/api/health` 返回 `ok`，前端 `http://127.0.0.1:3000/` 返回 200。
