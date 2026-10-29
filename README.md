# Aster（星枢）

**面向长任务的 AI 工作区与多智能体编排引擎。**

规划、拆解、执行、恢复与交付，多智能体协作全程可观测、可干预。

**在线体验** → https://www.zhuwenxuan.online

[快速开始](#快速开始) · [在线体验](#在线体验) · [文档](#文档) · [参与贡献](#参与贡献) · [联系](#联系)

## 简介

Aster 是一个基于 Spring Boot、Spring AI 和 Vue 3 的 AI 工作区：工作台把任务、工作流、知识库收敛到同一套外壳；超级智能体由规划器拆解任务、多个子智能体并行执行、质检员验收、交付器统一汇总，并在执行过程中调用工具、检索私有知识库、处理图片和文件。另有自包含的 macOS 桌面客户端，内置数据库与运行时，开箱即用。

## 能力

- **超级智能体** — Planner → Workers → Verifier → Aggregator 多智能体协作，支持多步任务与并行执行。
- **工作台与任务** — 服务总览、按工作区分组的任务侧栏、会话级模型切换与五档权限等级。
- **流式对话与续跑** — SSE 实时输出；支持断线重连、事件回放和跨重启检查点续跑。
- **工具调用** — 联网搜索、网页抓取、文件读写、PDF 生成、只读 SQL、终端白名单等。
- **私有知识库** — 上传 Markdown、TXT、PDF、Word、Excel、PPT，通过 MinerU 解析并写入 PGVector；回答带引用和切片预览。
- **知识审批** — 上传可勾选"需人工审核"，三阶段可视化；审核意见进入追加式审计时间线。
- **意图分流** — 自动识别消息意图，知识库检索/联网搜索按需开启；TypeSafe Jev 与 qwen 路由可配置切换。
- **图片理解** — 图片上传到私有 OSS，通过视觉模型或图片 MCP 分析。
- **工作流编排** — 原生图引擎与节点画布：开始/模型/生图/接口/数据库/模板/文件导出等节点，版本化保存与运行留证。
- **模型配置** — Provider → Model → 用途绑定的目录化配置；支持个人绑定、系统默认与环境变量回退，兼容 DashScope 与 OpenAI 兼容接口，密钥加密存储；对话输入框可切换模型并冻结到运行记录。
- **MCP 与 Skill** — 动态注册 MCP SSE 服务；支持 Skill 配置和工具授权。
- **安全与多用户** — JWT 登录、租户/用户数据隔离、工具沙箱、SSRF 与路径校验。

## 快速开始

### Docker 部署（推荐）

构建机需要 JDK 21、Maven、Node.js 和 Docker。

```bash
VERSION=0.0.1 TARGET_PLATFORM=linux/amd64 sh deploy/scripts/package.sh
```

产物位于 `release/aster-0.0.1-linux-amd64.tar.gz`。上传到服务器后：

```bash
tar -xzf aster-0.0.1-linux-amd64.tar.gz
cd aster-0.0.1-linux-amd64
cp .env.example .env
cp globe.conf.example /home/globe.conf
./install.sh
```

启动后访问 `http://<服务器地址>:8080`。完整部署、HTTPS、更新与数据迁移见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

### 本地开发

需要 JDK 21、Maven 3.9+、Node.js 18+。

```bash
# 1. 启动 PostgreSQL + pgvector
docker run -d --name aster-postgres \
  -e POSTGRES_DB=zwx_agent -e POSTGRES_USER=zwx_agent \
  -e POSTGRES_PASSWORD=zwx_agent_password \
  -p 5432:5432 -v aster-pgdata:/var/lib/postgresql/data \
  pgvector/pgvector:pg16

# 2. 配置本地参数
# 在 src/main/resources/ 下新建 application-local.yml（已被 Git 忽略）
# 填写数据库地址、DashScope API Key 等

# 3. 启动后端
mvn -DskipTests package
java -jar target/aster-0.0.1-SNAPSHOT.jar --spring.profiles.active=local

# 4. 启动前端
cd frontend && npm install && npm run dev -- --host 127.0.0.1
```

访问 `http://127.0.0.1:3000/`。首次启动自动创建 `admin`，默认密码 `admin123`。生产环境务必覆盖默认密码并设置稳定的 `JWT_SECRET`。

### 桌面端（macOS，Apple Silicon）

```bash
cd desktop
npm run dist:mac   # 构建前端 + 打包自包含运行时，产物在 desktop/release/
```

安装包会内置 PostgreSQL/pgvector 与 JRE，首次启动在 `~/Library/Application Support/aster-desktop/` 初始化本地数据库；本机模式通过回环令牌免登录，无需手工配置数据库。

### 可选服务

- **Docling Serve** — PDF / Office 解析，默认回退到本地解析，详见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。
- **图片理解 MCP** — 图片搜索 / 理解，需配置 DashScope API Key。
- **文档解析 MCP** — 调用 MinerU 精准解析，提供 `parse_document`。
- **网页截图 MCP** — 无头 Chromium 渲染并返回短时截图 URL。

## 在线体验

**https://www.zhuwenxuan.online**

- 主应用：`https://www.zhuwenxuan.online`
- 简历站：`https://www.zhuwenxuan.online/resume/`
- 管理员账号：`admin`（密码见 [部署文档](docs/DEPLOYMENT.md)）

> 备案通过前可通过 `http://1.13.152.215:8080` 直连访问。

## 文档

完整文档在 [`docs/`](docs/README.md)。常用入口：

- [服务器部署](docs/DEPLOYMENT.md)
- [架构与设计](docs/design/README.md)
- [功能进度](docs/ROADMAP.md)
- [会话交接](docs/context/README.md)

## 参与贡献

欢迎缺陷报告、文档修正与 PR。提交前请阅读项目规范，并遵守代码风格。安全问题请私密报告，勿在公开 Issue 中粘贴密钥。

## 授权

Aster 是个人项目，源码可见。如需商用或二次分发，请先联系作者。

## 联系

[GitHub Issues](https://github.com/20030325zwx-lgtm/zwx-agent/issues) · [www.zhuwenxuan.online](https://www.zhuwenxuan.online)

---
