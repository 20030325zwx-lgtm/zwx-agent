# Aster（星枢）

Aster 是一个基于 Spring Boot、Spring AI 和 Vue 3 的 AI 工作区，当前以**超级智能体**为唯一入口：由规划器拆解任务、多个子智能体并行执行、质检员验收、交付器统一汇总，并在执行过程中调用工具、检索私有知识库、处理图片和文件。

## 功能

- **超级智能体**：Planner → Workers → Verifier → Aggregator 多智能体协作，支持多步任务与并行执行。
- **流式对话与续跑**：SSE 实时输出；支持断线重连、事件回放和跨重启检查点续跑。
- **工具调用**：联网搜索、网页抓取、文件读写、PDF 生成、只读 SQL、终端白名单等。
- **私有知识库**：上传 Markdown、TXT、PDF、Word、Excel、PPT，默认通过 MinerU 在线解析，自动切片并写入 PGVector；回答带引用和切片预览。
- **图片理解**：图片上传到私有 OSS，通过视觉模型或图片 MCP 分析图片内容。
- **模型配置**：`/model-settings` 支持个人配置、管理员系统默认和环境变量回退；兼容 DashScope 与 OpenAI 兼容接口，密钥加密存储。
- **MCP 与 Skill**：动态注册 MCP SSE 服务；支持 Skill 配置和工具授权。
- **安全与多用户**：JWT 登录、租户/用户数据隔离、工具沙箱、SSRF 与路径校验。

## 技术栈

- 后端：Java 21、Spring Boot 3.4、Spring AI、Spring AI Alibaba Graph
- 数据库：PostgreSQL + pgvector（Flyway 自动迁移）
- 前端：Vue 3、Vite、Vue Router
- 对象存储：阿里云 OSS 或 MinIO
- 可选服务：Docling Serve（文档解析）、图片搜索/理解 MCP

## 本地启动

### 1. 前置条件

- JDK 21
- Maven 3.9+
- Node.js 18+
- Docker（用于 PostgreSQL，可选）
- 阿里云 DashScope API Key

### 2. 启动 PostgreSQL + pgvector

```bash
docker run -d --name aster-postgres \
  -e POSTGRES_DB=zwx_agent \
  -e POSTGRES_USER=zwx_agent \
  -e POSTGRES_PASSWORD=zwx_agent_password \
  -p 5432:5432 \
  -v aster-pgdata:/var/lib/postgresql/data \
  pgvector/pgvector:pg16
```

### 3. 配置本地参数

在 `src/main/resources/` 下新建 `application-local.yml`（该文件已被 Git 忽略，不要提交）：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/zwx_agent
    username: zwx_agent
    password: zwx_agent_password
  ai:
    dashscope:
      api-key: YOUR_DASHSCOPE_API_KEY

app:
  oss:
    endpoint: https://oss-cn-hangzhou.aliyuncs.com
    bucket: YOUR_BUCKET
    access-key-id: YOUR_ACCESS_KEY_ID
    access-key-secret: YOUR_ACCESS_KEY_SECRET
```

OSS 仅在使用图片上传和知识库文档上传时需要；不配置也可以正常对话。

### 4. 启动后端

```bash
mvn -DskipTests package
java -jar target/aster-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

后端地址：`http://127.0.0.1:8123/api`  
健康检查：`GET http://127.0.0.1:8123/api/health`

### 5. 启动前端

```bash
cd frontend
npm install
npm run dev -- --host 127.0.0.1
```

访问 `http://127.0.0.1:3000/`。首次启动且用户表为空时，会自动创建管理员 `admin`，默认密码 `admin123`。生产环境请通过 `APP_SECURITY_ADMIN_PASSWORD` 覆盖，并设置稳定的 `JWT_SECRET`。

## Docker 部署

推荐使用离线安装包部署到服务器。构建机需要 JDK 21、Maven、Node.js 和 Docker。

### 1. 构建安装包

```bash
VERSION=0.0.1 TARGET_PLATFORM=linux/amd64 sh deploy/scripts/package.sh
```

产物位于 `release/aster-0.0.1-linux-amd64.tar.gz`。

### 2. 服务器安装

```bash
tar -xzf aster-0.0.1-linux-amd64.tar.gz
cd aster-0.0.1-linux-amd64

cp .env.example .env
# 编辑 .env，至少填写 POSTGRES_PASSWORD、DASHSCOPE_API_KEY 和 MINERU_TOKEN

cp globe.conf.example /home/globe.conf
# 编辑 /home/globe.conf，至少填写 app.security.jwt-secret

./install.sh
```

启动后访问 `http://<服务器地址>:8080`。完整部署、HTTPS、更新、回滚和数据迁移见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。停止服务：

```bash
./stop.sh
```

服务器需要 Docker 和 Docker Compose v2。`install.sh` 会加载镜像、启动 PostgreSQL、后端、前端、图片 MCP 和文档 MCP，并自动注册 MCP 配置；网页截图 MCP 默认关闭，需要时用 `WITH_BROWSER_MCP=true` 打包并设置 `BROWSER_MCP_ENABLED=true`。默认 `APP_DOCUMENT_PARSER_PROVIDER=mineru`、`APP_DOCUMENT_PARSER_DOCLING_ENABLED=false`，因此 4核8G 服务器无需运行 Docling。

## 可选服务

### Docling Serve（PDF / Office 解析）

```bash
cd deploy/docker-compose/docling-serve
./build-runtime.sh
docker compose up -d --build
```

默认地址 `http://127.0.0.1:5001`。未启动时后端会按配置回退到本地解析。

### 图片搜索 / 理解 MCP

```bash
cd services/image-search-mcp
cp .env.example .env
# 编辑 .env，填写 DASHSCOPE_API_KEY
docker compose up -d --build
```

默认地址 `http://127.0.0.1:8127`，只绑定本机。

### 文档解析 MCP

```bash
cd services/document-mcp
cp .env.example .env
# 编辑 .env，填写 MINERU_TOKEN
mvn -DskipTests package
set -a && . ./.env && set +a
java -jar target/aster-document-mcp-server-0.0.1-SNAPSHOT.jar
```

默认地址 `http://127.0.0.1:8128`。提供 `parse_document`，默认调用 MinerU 精准解析 API；Docling、Tika 仅作为可选回退。

### 网页截图 MCP

```bash
cd services/browser-mcp
npm install
CHROME_EXECUTABLE_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  npm start
```

默认地址 `http://127.0.0.1:8129`。提供 `screenshot_page`，用无头 Chromium 渲染网页并返回短时截图 URL。Docker 部署可使用 `docker compose up -d --build`。

## 常用地址

- 前端：`http://127.0.0.1:3000/`
- 后端 API：`http://127.0.0.1:8123/api`
- 健康检查：`http://127.0.0.1:8123/api/health`
- Swagger：`http://127.0.0.1:8123/api/swagger-ui.html`（开发环境）
- 模型设置：`/model-settings`
- 知识库：`/knowledge-admin`
- MCP 管理：`/mcp-settings`

## 文档

- 服务器部署：[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)
- 架构与设计：[docs/design/README.md](docs/design/README.md)
- 功能进度：[docs/ROADMAP.md](docs/ROADMAP.md)
- 会话交接：[docs/context/README.md](docs/context/README.md)
- 原项目：[liyupi/yu-ai-agent](https://github.com/liyupi/yu-ai-agent)
