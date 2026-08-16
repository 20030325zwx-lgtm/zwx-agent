# ZWX Agent

ZWX Agent 是一个基于 Spring Boot 和 Vue 3 的 AI 应用项目，当前包含面向情感咨询场景的“情感分析大师”对话功能，以及可扩展的 RAG、工具调用、MCP 和自主规划智能体能力。

## 原项目与作者

本项目基于 [程序员鱼皮（liyupi）的 yu-ai-agent](https://github.com/liyupi/yu-ai-agent) 二次开发。

- 原作者：程序员鱼皮（liyupi）
- 原项目仓库：[github.com/liyupi/yu-ai-agent](https://github.com/liyupi/yu-ai-agent)
- 本仓库在保留原项目技术基础上进行了品牌、情感分析流程、图片多模态、会话持久化、PGVector RAG、引用与调用链可视化等改造。

## 当前能力

- 多轮 AI 对话：默认使用阿里云 DashScope 的 `qwen-plus`。
- 情感分析大师：提供针对情感问题的对话引导、关系分析与建议。
- 会话持久化：情感分析大师的会话和消息保存在 PostgreSQL；模型请求采用最近 20 条消息作为上下文窗口。
- 图片多模态：支持选择文件和粘贴图片，使用 `qwen-vl-plus` 理解图片内容。
- 私有图片存储：图片上传至阿里云 OSS，后端生成短时签名读取地址供视觉模型访问；聊天历史通过受控接口读取图片。
- 扩展能力：项目保留了 RAG、PGVector、MCP、联网搜索、文件操作、网页抓取、资源下载、PDF 生成和 ReAct 智能体相关模块。

## 技术栈

- Java 21、Spring Boot 3.4、Spring AI
- 阿里云 DashScope SDK、阿里云 OSS SDK
- PostgreSQL、PGVector
- Vue 3、Vite、Vue Router

## 目录说明

```text
.
├── src/                              # Spring Boot 后端
├── zwx-agent-frontend/               # Vue 3 前端
└── zwx-image-search-mcp-server/      # 可选的图片搜索 MCP 服务
```

## 前置条件

- JDK 21
- Maven 3.9+
- Node.js 18+
- PostgreSQL 及 PGVector（使用会话持久化和 RAG 时需要）
- DashScope API Key
- 阿里云 OSS Bucket 与具备对象读写权限的 RAM AccessKey（使用图片功能时需要）

## 本地配置

默认配置位于 `src/main/resources/application.yml`，本地敏感配置放入同目录的 `application-local.yml`。后者已被 Git 忽略，禁止提交。

可按实际环境创建以下配置，所有值仅作占位示例：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/zwx_agent
    username: YOUR_DB_USER
    password: YOUR_DB_PASSWORD
  ai:
    dashscope:
      api-key: YOUR_DASHSCOPE_API_KEY

app:
  oss:
    endpoint: https://oss-cn-hangzhou.aliyuncs.com
    bucket: YOUR_OSS_BUCKET
    access-key-id: YOUR_OSS_ACCESS_KEY_ID
    access-key-secret: YOUR_OSS_ACCESS_KEY_SECRET
```

图片功能使用的 RAM 权限至少应包括：

- `oss:PutObject`
- `oss:GetObject`
- `oss:DeleteObject`
- `oss:ListObjects`

建议将权限限制在目标 Bucket 和图片对象前缀内。OSS Bucket 保持私有访问，应用会生成临时签名 URL，不需要将 Bucket 公开。

## 启动后端

```bash
mvn spring-boot:run
```

后端默认地址为 `http://127.0.0.1:8123/api`，健康检查为：

```text
GET http://127.0.0.1:8123/api/health
```

接口文档地址：`http://127.0.0.1:8123/api/swagger-ui.html`。

## 启动前端

```bash
cd zwx-agent-frontend
npm install
npm run dev -- --host 127.0.0.1
```

情感分析大师页面：`http://127.0.0.1:3000/love-master`。

## 验证构建

```bash
# 后端
mvn -DskipTests clean compile

# MCP 子服务
cd zwx-image-search-mcp-server
mvn -DskipTests clean compile

# 前端
cd ../zwx-agent-frontend
npm run build
```

## 图片对话流程

1. 前端通过文件选择或剪贴板粘贴图片。
2. 前端将图片上传到后端，后端校验 JPEG、PNG、GIF 格式及 10 MB 大小限制。
3. 后端将图片保存到私有 OSS，并仅在视觉模型请求时生成 15 分钟有效的签名 GET URL。
4. 后端将文本和图片 URL 作为多模态消息传给 `qwen-vl-plus`。
5. 消息记录保存图片对象键；恢复历史会话时，前端通过后端受控图片接口显示图片。

请避免上传宽或高不大于 10 像素的图片，视觉模型会拒绝此类图片。

## 安全说明

- 不要在 `application.yml`、README、提交记录或前端代码中写入 API Key、AccessKey、密码或 Token。
- `application-local.yml`、`.env*` 和 `.DS_Store` 已被 Git 忽略；提交前仍应使用 `git status` 检查暂存内容。
- 已暴露的 GitHub Token、云端 AccessKey 或模型 API Key 应立即撤销并重新生成。
