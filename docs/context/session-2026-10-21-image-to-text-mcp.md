# 会话摘要：图片转文本 MCP 与超级智能体图片输入（2026-10-21）

## 设计

- 新增 `docs/design/2026-10-20-image-to-text-mcp.md`，并加入 `docs/design/README.md` 索引。
- 数据流：主应用上传图片到私有 OSS → 生成短时签名 URL → MCP `describe_image(image_url, question)` → DashScope 通过 URL 读取图片 → 返回文本 → 主应用用作检索 query/上下文。
- MCP 只接收 URL，不接收图片二进制；不把 base64 放入工具参数。

## 实现

- `services/image-search-mcp` 新增 `ImageUnderstandingTool`：
  - `describe_image(image_url, question)`：返回 summary、相关文字、uncertain_items、retrieval_query。
  - `ocr_image(image_url)`：完整 OCR。
  - URL 仅允许 `*.aliyuncs.com`、localhost、127.0.0.1（可配置）。
  - 默认模型 `qwen3-vl-plus`；`qwen-vl-plus` 在当前账号返回 `Model not exist`。
- 主应用：
  - 新增 `AgentImageStorageService` 和 `AgentImageUpload/Content`。
  - 新增 `POST /api/ai/manus/images`、`GET /api/ai/manus/images`。
  - `GET /api/ai/manus/chat` 新增 `imageObjectKey`。
  - 新增 `ImageAnalysisService`：查找 MCP `describe_image`，调用后解析结果；MCP 不可用或空结果时明确标记失败。
  - `ImageAnalysisResult` 注入上下文，`retrieval_query` 用于知识检索。
- 前端：
  - 超级智能体启用图片附件（JPEG/PNG/GIF/WebP），上传后显示图片并传 `imageObjectKey`。
  - `chatWithManus`、`uploadManusImage`、`getManusImageUrl` 已接入。
- 模型默认值：
  - 主应用 `app.love.vision-model` 改为 `qwen3-vl-plus`。
  - MCP 服务 `dashscope.vision-model` 改为 `qwen3-vl-plus`。

## MCP Docker 部署（追加）

- `services/image-search-mcp` 已增加独立 Docker 部署文件：
  - `Dockerfile`：Maven 多阶段构建，运行时使用 `eclipse-temurin:21-jre-alpine`；
  - `docker-compose.yml`：容器名 `services/image-search-mcp`，镜像 `services/image-search-mcp:local`，端口仅绑定 `127.0.0.1:8127`；
  - `HealthController`：新增 `GET /health`，compose 健康检查使用该接口；
  - `.env.example`、`.dockerignore`、模块 README。
- 代码仍保留在当前仓库，MCP 主应用配置仍指向 `http://127.0.0.1:8127`。
- 本地 Docker 镜像源 `docker.xuanyuan.me` 返回 403，已通过 `docker.m.daocloud.io` 拉取 Maven/Temurin 镜像并打回标准 tag 后完成构建；后续若重建失败，优先检查 Docker registry mirror。

## 验证

- MCP 服务启动后暴露 3 个工具：`describe_image`、`ocr_image`、`searchImage`。
- `getCurrentTime` 已从 MCP 移除，改为主应用内置工具 `com.zwx.zwxagent.tools.CurrentTimeTool`，由 `ToolFactory` 注册给超级智能体/情感分析大师等使用该工厂的链路。
- 通过 `POST /api/ai/manus/images` 上传 1047×2431 的江苏录取分数表图片，生成 OSS 签名 URL。
- 超级智能体真实 SSE 对话：
  - MCP `describe_image` 约 7 秒返回摘要、相关文字和 query；
  - 最终回答“图片里的最低录取分数线是 517 分”。
- `mvn -DskipTests package`（主应用、MCP 服务）成功；后端重启后 `/api/health` 返回 `ok`。
- 曾尝试完整 OCR prompt，DashScope 需要约 128 秒；已改为 `describe_image` 只返回与问题相关的短文本，`ocr_image` 才做完整 OCR。

## 遗留事项

- 图片对象键已通过 V15 持久化到 `agent_chat_message.image_object_keys`，前端历史消息会重新加载并显示；本地已验证历史接口返回图片键。
- MCP 服务目前用 nohup 手动启动，未配置 launchctl 常驻任务；本地数据库已插入 `default/图片理解 -> http://127.0.0.1:8127` 配置。
- `ocr_image` 尚未在真实超级智能体链路中端到端验证。
- MCP 客户端请求超时统一提升到 90 秒，可能影响其他 MCP 工具的失败反馈速度。
- 临时调试端点、payload 落盘和调试日志已清理。
- Docker 容器 `services/image-search-mcp` 已启动并通过健康检查；主应用 MCP 测试发现 3 个工具（图片相关）。
- `CurrentTimeToolTest` 通过。
- 通过 Docker MCP 完成真实图片问答：上传图片 → `describe_image` → 回答最低录取分数线 517 分。
- 本轮改动尚未提交。

## 环境注意事项

- `src/main/resources/application-local.yml` 未编辑、未复制、未提交；仅读取了 OSS endpoint/bucket 等非敏感配置用于确认对象存储。
- V15 `persist_message_image_keys` 已在本地 PostgreSQL 应用。
- 后端由 launchctl 管理；图片 MCP 进程监听 `127.0.0.1:8127`。
- 签名 URL 15 分钟内有效，测试时注意过期后重新上传/生成。
