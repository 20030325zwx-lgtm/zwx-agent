# 模型配置与分层覆盖（2026-10-21）

## 目标

- 部署后模型与外部服务默认从环境变量读取，无需改代码。
- 管理员可以维护系统默认配置；普通用户可以覆盖自己的模型。
- 生效顺序：**用户配置 > 系统默认 > 环境变量**，逐字段回退。
- 密钥加密落库，接口只返回掩码，不返回明文。

## 数据模型

V16 新增 `app_configuration`，一张表同时支持模型与后续外部服务：

| 字段 | 说明 |
| --- | --- |
| `scope_type` | `SYSTEM` / `USER` |
| `owner_id` | USER 作用域的用户 ID；SYSTEM 为空字符串 |
| `config_type` | `MODEL` / `SERVICE` |
| `config_key` | `chat`、`vision`，后续可扩展 `docling`、`oss` 等 |
| `provider` | `dashscope` / `openai-compatible` |
| `base_url` | 模型服务地址；空表示继承 |
| `model_name` | 模型名称；空表示继承 |
| `secret_ciphertext` | AES-GCM 密文，禁止明文落库 |
| `secret_hint` | 末位掩码，例如 `****1234` |
| `enabled` | 关闭后整行视为不存在，回退到下一层 |
| `extra` | JSONB 扩展位 |

唯一索引：`(scope_type, owner_id, config_type, config_key)`。

## 密钥与地址安全

- AES-GCM，密钥由 `APP_CONFIG_ENCRYPTION_KEY` 经 SHA-256 派生；未配置时本地回退 `JWT_SECRET`，生产必须单独配置。
- 返回给前端的内容只包含 `secretHint` 与 `hasSecret`。
- 用户填写的 `base_url` 仅允许无凭据、无片段的 HTTP(S) 地址；域名需在 `APP_CONFIG_MODEL_HOST_ALLOWLIST`（默认 DashScope/OpenAI）中，或通过公网地址校验（阻止回环、内网、链路本地、ULA 等）。
- 修改密钥或配置后清空解析缓存；模型实例缓存按配置键隔离。

## 运行时切换

- `AppConfigurationService.resolve(userId, key)` 负责分层合并，带 30 秒 TTL 缓存。
- `DynamicChatModelFactory` 按 `provider + baseUrl + model + key` 构建并缓存 Spring AI `ChatModel`：
  - DashScope：`DashScopeChatModel` + 独立 `DashScopeApi`。
  - OpenAI 兼容：`OpenAiChatModel` + 独立 `OpenAiApi`；`baseUrl` 以 `/v1` 结尾时使用 `/chat/completions`。
- 情感、旅游、测试智能体在每次请求时解析用户配置并构建 `ChatClient`。
- 超级智能体每次 run 都构建图，`ManusRunRequest` 携带本次生效的 `ChatModel`。
- 视觉模型按 provider 分流：DashScope 走原生多模态 SDK（URL 图片稳定），OpenAI 兼容走 Spring AI `Media`。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/settings/models` | 生效值、个人覆盖、系统默认 |
| PUT | `/api/settings/models/{key}` | 保存个人覆盖 |
| DELETE | `/api/settings/models/{key}` | 删除个人覆盖，恢复继承 |
| PUT | `/api/settings/system/models/{key}` | 管理员保存系统默认 |
| DELETE | `/api/settings/system/models/{key}` | 管理员清除系统默认 |
| POST | `/api/settings/models/{key}/test?scope=USER\|SYSTEM` | 用当前生效配置做一次最小调用 |

## 前端

`/model-settings` 页面：

- 展示每个模型的当前生效值、来源（个人/系统/环境）和密钥掩码。
- 普通用户维护“我的配置”，管理员额外维护“系统默认”。
- 支持测试连接、恢复继承、清除已保存密钥。

## 边界与后续

- 向量模型与重排序模型暂不做用户级覆盖：embedding 变更会破坏 pgvector 维度与现有索引，需配合重建索引流程。
- 外部服务（Docling、Tika、OSS、MinIO、MCP、搜索）本次只预留表结构与 `config_type`，运行时热读取在后续批次接入。
- 多实例部署时配置缓存最多滞后 30 秒；如需强一致可改为事件通知或缩短 TTL。
