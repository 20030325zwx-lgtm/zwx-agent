# 会话摘要：模型配置表与分层覆盖（2026-10-21）

## 改动内容

- 新增 V16 `app_configuration`：`SYSTEM/USER`、`MODEL/SERVICE`、`config_key`、provider/baseUrl/model、AES-GCM 密文与掩码、启用状态、JSONB 扩展位。
- 新增后端 `configuration` 包：
  - `ConfigSecretCipher`：AES-GCM 加解密与 `****1234` 掩码。
  - `AppConfigurationService`：用户 > 系统 > 环境变量逐字段合并，30 秒解析缓存，写入/删除后失效。
  - `DynamicChatModelFactory`：按配置构建并缓存 DashScope / OpenAI 兼容 `ChatModel`。
  - `ModelConfigurationProbe`：最小调用测试模型连通性。
- 新增 `SettingsController`（`/api/settings`）：个人配置、系统默认、恢复继承、测试连接；系统配置要求 ADMIN。
- 运行时接入：
  - 情感、旅游、测试智能体每次请求按用户解析对话模型。
  - 超级智能体 `ManusRunRequest` 携带本次生效的 `ChatModel`，图按 run 构建。
  - 视觉模型按 provider 分流：DashScope 原生多模态 SDK；OpenAI 兼容走 Spring AI `Media`。
  - RAG trace 中记录本次实际使用的模型名。
- 前端新增 `/model-settings`：左侧模型导航 + 右侧详情面板，页眉 sticky 固定；生效值、来源、密钥掩码、个人/系统 Tab、测试连接、恢复继承；首页和智能体设置菜单加入入口。
- 首页改版：
  - 顶栏改为统一分段导航（智能体/模型/Skill/MCP/知识库），右侧仅保留本机服务与账号。
  - Hero 增加动态极光、漂浮图标、轮播标题、渐变流光、⌘K 聚焦搜索。
  - 智能体卡片增加聚光跟随、入场动画；超级智能体卡片增加“规划→执行→验收→交付”流程动效。
  - 全部动效支持 `prefers-reduced-motion` 降级。
- 工作区导航改为持久布局：
  - 新增 `WorkspaceLayout`，顶部导航在首页/模型/Skill/MCP/知识库之间常驻。
  - 导航增加滑块指示器；切换时内容按方向滑入滑出（160-220ms，带轻微模糊），不再整页跳转。
  - 路由改为 `/` 下的嵌套子路由；模型页去掉独立 sticky 页眉，知识库页高度适配常驻导航。
  - 顶栏在滚动时固定在顶部，页面内容从导航下方开始，不再重叠。
  - Skill/MCP 页标题改为透明背景并收进 900px 内容宽度，去掉生硬的整条白色页眉。
  - 知识库页标题改为透明内容头部，移除返回主页按钮；资料归属改为带智能体图标的自定义下拉，默认选中超级智能体。
  - 移除知识库页独立的玫瑰色主题设置入口，工作区统一跟随顶栏的蓝色主题。
- 依赖新增 `spring-ai-openai`（仅核心模块，不启用 OpenAI 自动配置）。
- 文档：`docs/design/2026-10-21-model-configuration.md`、`docs/design/README.md`、`README.md`、`docs/ROADMAP.md`。

## 验证结果

- `mvn -q -DskipTests package` 成功；后端重启后 `/api/health` 返回 `ok`；Flyway V16 已应用。
- 新单测 `ConfigSecretCipherTest`、`DynamicChatModelFactoryTest` 通过。
- 真实接口验证（临时用户，验证后已清理 DB）：
  - 环境变量兜底：`GET /api/settings/models` 返回 `chat=qwen-plus`、`vision=qwen3-vl-plus`，来源 `ENVIRONMENT`。
  - 个人覆盖保存后来源变为 `USER`；测试连接 `qwen-plus` 返回 `OK`。
  - 保存测试密钥后 DB 为 64 字节密文且不含明文，接口只返回 `****9876`；恢复继承后回到 `ENVIRONMENT`。
  - 管理员可保存/清除系统默认；普通用户访问系统接口返回 403。
  - 情感对话走个人/默认 ChatModel 正常流式返回。
  - 视觉消息在 DashScope provider 下成功识别图片并生成检索 query。
  - OpenAI 兼容 provider 指向 `https://dashscope.aliyuncs.com/compatible-mode/v1`，对话测试与视觉识别均成功。
- 前端 `npm run build` 成功；`/model-settings` 路由返回 200；已用无头 Chrome 截图核对新布局（左侧模型导航、右侧单面板、sticky 页眉）。
- 使用 Playwright 验证五个导航项：URL、激活态、对应页面组件均正确；滚动 500px 后顶栏仍固定在顶部。
- 全量 `mvn test`：196 个测试，5 个错误，均为既有测试环境问题：
  - `LoveAppTest` 的 demo 方法使用随机 chatId 但未创建 `love_conversation`，触发外键约束。
  - `PgVectorVectorStoreConfigTest` 注入 `VectorStore` 时存在两个 Bean，类型不唯一。
  本轮新增测试均通过。

## RAG 回归修复（追加）

- 现象：同一“先维员工迟到几次不扣钱”问题，昨天能命中 `员工手册#14`，今天回答“未载明”。
- 根因：
  - 超级智能体知识库新增 676 个地理切片后，`lexicalRecall` 仍使用无排序的 `LIMIT 300`，员工手册切片不在前 300 行，词法召回整体漏掉。
  - DashScope rerank 请求没有带 `Authorization` 头，一直 401 失败并降级；失去重排后按融合顺序取前 3，把向量第 3 的相关切片挤出。
- 修复：
  - 词法召回改为 SQL 内按关键词命中数打分、排序后再截断；中文 bigram 词项不再被整句占用，确保“迟到/扣钱”进入检索。
  - rerank 请求补 `Authorization: Bearer <key>`；新增本地 HTTP 测试验证请求头。
  - 新增词法词项单测，防止长中文句吞掉关键词名额。
- 验证：同一问题现在命中 `员工手册#14`，trace 显示 `reranked=true`、vectorRank=3、lexicalRank=1、rerankRank=1，回答“每月不超过3次且每次超时15分钟内不扣钱”。本轮修复尚未提交。

## 遗留事项

- 外部服务（Docling、Tika、OSS、MinIO、搜索、MCP）尚未接入运行时热读取；表结构已预留 `config_type=SERVICE`。
- embedding/rerank 仍是系统级环境变量，页面未开放个人覆盖；变更 embedding 需要重建索引。
- 用户自定义 baseUrl 采用域名白名单 + 公网校验；如需本地 Ollama/私有网关，需通过 `APP_CONFIG_MODEL_HOST_ALLOWLIST` 放行。
- 多实例配置缓存最多滞后 30 秒。
- 本轮改动尚未提交。

## 环境注意事项

- 未编辑、未复制、未提交 `src/main/resources/application-local.yml`。
- 生产部署应单独设置 `APP_CONFIG_ENCRYPTION_KEY`；当前未配置时回退 `JWT_SECRET`，仅适合本地开发。
- OpenAI 兼容 baseUrl 以 `/v1` 结尾时自动使用 `/chat/completions`，避免重复拼接 `/v1`。
- 测试使用的临时用户、会话与配置已清理；上传到 OSS 的测试图片对象仍保留。
