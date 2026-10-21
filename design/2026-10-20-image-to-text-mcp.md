# 图片转文本 MCP 集成设计

## 目标

让用户可以把图片作为问题输入：主应用接收图片、写入对象存储并生成短时签名 URL；MCP 服务提供 OCR/视觉理解工具；主应用把工具返回的文本作为检索 query 或用户问题交给大模型。

## 非目标

- 不让 MCP 直接接收聊天图片二进制，也不把 base64 塞进工具参数；MCP 只接收签名 URL。
- 不让 MCP 访问对象存储 AK/SK。
- 不改变现有情感分析大师的图片链路；先为超级智能体打通通用能力。

## 当前状态

- 主应用实际使用 `app.oss.*`：阿里云 OSS，bucket `zwx-agent`；`app.storage.*` 的 MinIO 配置当前没有代码引用。
- `LoveImageStorageService.presignedReadUrl` 已能生成 15 分钟有效的签名 GET URL，但 key 前缀限定为 `love/{conversationId}/`。
- MCP 只在超级智能体加载 tool callbacks；`zwx-image-search-mcp-server` 只有 `searchImage` 和时间工具，没有 OCR/视觉理解。
- 超级智能体前端目前没有图片上传入口。

## 架构

```text
用户上传图片
  -> 主应用 POST /manus/images（校验类型/大小）
  -> 写入 OSS  super/{conversationId}/...
  -> 生成 15 分钟签名 URL（只在服务端使用）
  -> 主应用调用 MCP describe_image(image_url, question)
  -> MCP 校验 URL allowlist，并把签名 URL 传给 qwen-vl
  -> DashScope 通过签名 URL 读取图片，返回结构化文本（摘要、识别文字、不确定项、建议 query）
  -> 主应用把 query 用于 RAG/规划，把视觉摘要作为不可信资料注入上下文
  -> 大模型生成最终回答
```

### 为什么由主应用直接调用 MCP 工具

- 结果确定：不依赖模型是否记得调用工具。
- 不把签名 URL 暴露给主模型，减少泄露范围。
- MCP 仍可替换：主应用只依赖工具名和 JSON 契约。

## MCP 工具契约

服务：扩展现有 `zwx-image-search-mcp-server`（SSE，`127.0.0.1:8127`）。

### `describe_image`

参数：

- `image_url`：短时签名 URL。
- `question`：用户原始问题，可为空。

返回 JSON 字符串：

```json
{
  "summary": "图片内容概括，最多 200 字",
  "extracted_text": "OCR 识别文字，最多 4000 字",
  "uncertain_items": ["无法确认的点"],
  "retrieval_query": "用于 RAG 的中文查询，最多 180 字"
}
```

### `ocr_image`

参数：

- `image_url`：短时签名 URL。

返回纯文本 OCR 结果，最多 4000 字。

## 主应用改动

### 通用图片存储

- 新增 `AgentImageStorageService`（或把 `LoveImageStorageService` 泛化为 `ImageStorageService`）：
  - 支持 `jpg/jpeg/png/gif/webp`，最大 10 MB；
  - 对象 key：`{agentKey}/{conversationId}/{uuid}.{ext}`；
  - `presignedReadUrl(objectKey)` 生成 15 分钟 URL；
  - 校验当前用户/会话对该 key 的归属。
- 新增接口：

```text
POST /api/ai/manus/images?conversationId=...
GET  /api/ai/manus/images?conversationId=...&objectKey=...   # 前端展示用，走 JWT 代理
```

### 超级智能体聊天参数

`GET /api/ai/manus/chat` 增加可选 `imageObjectKey`（可重复或逗号分隔）。

主应用在 RAG 之前：

1. 校验图片归属并生成签名 URL；
2. 调用 MCP `describe_image`；
3. 用返回的 `retrieval_query` 替代/补充用户文本做知识检索；
4. 把 `summary/extracted_text/uncertain_items` 作为“不可信图片分析资料”加入上下文；
5. 如果 MCP 不可用，降级为直接调用 DashScope VLM；仍失败则明确告知用户图片未解析。

### MCP 工具调用

- 新增 `McpToolInvoker`：按工具名查找已启用 MCP 服务的 callback，传入 JSON 参数并解析结果。
- 只允许主应用内部调用，不新增对普通用户开放的任意工具调用接口。
- 工具结果截断：摘要 200 字、OCR 4000 字、query 180 字。

## MCP 服务改动

- 新增 `ImageUnderstandingTool`：
  - 使用 DashScope 多模态接口（默认 `qwen3-vl-plus`，复用 `DASHSCOPE_API_KEY`）；
  - 调用前校验 scheme 为 `http/https`，host 必须在允许列表（默认 `*.aliyuncs.com` 和 `127.0.0.1/localhost` 开发地址）；
  - 把短时签名 URL 直接交给 DashScope，由模型服务读取图片；MCP 不下载、不接收二进制；
  - `describe_image` 只要求与问题相关的简短摘要/文字和检索 query，`ocr_image` 才做完整 OCR；
  - 只把图片 URL 和问题发给模型，不执行图片中的指令。
- OCR 提示词要求保留原文顺序、表格尽量用 Markdown；视觉描述要求区分可观察内容与推测。
- 不记录完整签名 URL，只记录 host、对象 key 前缀和耗时。

## 安全与治理

- 签名 URL 15 分钟有效；不写日志、不回传给主模型。
- 图片 MIME 与文件头双重校验；限制尺寸和像素。
- MCP 只接受 allowlist 中的图片 URL；不接收任意二进制，也不把工具变成下载代理。
- OCR 文本是不可信数据，不能覆盖系统提示词或触发工具权限提升。
- 后续可把图片对象纳入数据保留/删除策略。

## 降级与错误

| 场景 | 行为 |
| --- | --- |
| MCP 未配置/不可用 | 尝试 DashScope VLM 直连 |
| 签名 URL 过期 | 重新生成一次后重试 |
| 图片过大/格式不支持 | 返回明确错误，不进入模型 |
| OCR 为空 | 只使用视觉摘要和用户文本 |
| MCP 返回非 JSON | 将原文截断后作为视觉摘要，标记 `degraded=true` |

## 测试

- MCP 工具单测：URL allowlist、超时、下载上限、OCR/描述 JSON 解析。
- 主应用单测：图片归属校验、签名 URL 生成、MCP 不可用降级、query 拼接。
- 端到端：上传一张含文字的截图 -> 超级智能体回答引用识别文字；再上传一张无文字照片 -> 使用视觉摘要检索。
- 安全：伪造 objectKey、过期 URL、非图片文件、超限文件。

## 验证结果（2026-10-21）

- 已上传一张 1047×2431 的江苏录取分数表图片到对象存储，生成 15 分钟签名 URL。
- MCP `describe_image` 通过签名 URL 调用 `qwen3-vl-plus`，约 7 秒返回摘要、相关文字和检索 query。
- 主应用把返回内容注入上下文，超级智能体最终回答“最低录取分数线为 517 分”。
- 实测 `qwen-vl-plus` 在当前账号不可用，已切换为 `qwen3-vl-plus`；主应用情感分析的默认视觉模型同步更新。

## 实施顺序

1. MCP 服务新增 `describe_image` / `ocr_image`。
2. 主应用通用图片存储与上传接口。
3. 主应用 MCP invoker 与超级智能体图片预处理。
4. 前端超级智能体图片上传与展示。
5. 端到端测试与文档。

## 配置

```yaml
app:
  image:
    max-bytes: 10485760
    signed-url-seconds: 900
  mcp:
    image-tools:
      enabled: true
      describe-tool: describe_image
      ocr-tool: ocr_image
```

MCP 服务：

```yaml
dashscope:
  api-key: ${DASHSCOPE_API_KEY:}
  vision-model: ${DASHSCOPE_VISION_MODEL:qwen3-vl-plus}
  allowed-image-hosts: ${IMAGE_ALLOWED_HOSTS:*.aliyuncs.com,localhost,127.0.0.1}
```
