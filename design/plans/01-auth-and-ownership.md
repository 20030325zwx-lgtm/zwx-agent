# 方案 01：认证与会话归属

## 1. 目标

让"谁能读/写哪条数据"由服务端身份决定，而不是客户端自报。完成后：

- 所有接口需要登录态；`tenantId` 由认证身份派生，删除一切客户端可传的租户参数。
- 会话、消息、图片、知识库文档、生成文件全部绑定归属者，横向越权（枚举 chatId/objectKey 读他人数据）不可行。
- 管理类接口（知识库上传、Skill/MCP 配置）需要管理员角色。

## 2. 现状问题（审计证据）

| 问题 | 位置 |
| --- | --- |
| 无任何认证依赖与过滤链（无 spring-security、无 Filter/Interceptor），40+ 端点匿名 | `pom.xml`、`controller/AiController.java` 全文件 |
| 租户自报：`X-Tenant-Id` 头（默认 `default`），SSE 用 query 参数，仅正则校验格式 | `AiController.java:152、215、290、530、600、629-631` |
| `love_conversation`/`love_chat_message` 无 tenant/user 列，`listConversations()` 无 WHERE 返回全库 | `db/migration/V1__create_application_tables.sql:1-6`、`conversation/LoveConversationService.java:31-45、66-77` |
| `chatId` 客户端任意指定、不校验 UUID，`ensureConversation` 直接 upsert 他人会话 | `AiController.java:273-290`、`LoveConversationService.java:37-45` |
| 知识库公共接口按 objectKey 返回全文与切片，可跨租户构造 `knowledge/{tenant}/...` 读取 | `AiController.java:247-255`、`rag/LoveKnowledgeAdminService.java:57-78` |
| 图片接口仅校验 objectKey 前缀 `love/{chatId}/`，无所有权校验 | `storage/LoveImageStorageService.java:70-75`、`AiController.java:257-264` |
| Manus 生成文件存全局共享目录，getManusFile 只验会话存在不验归属 | `constant/FileConstant.java:11-21`、`AiController.java:513-527` |
| Skill/MCP 配置匿名可改任意租户 | `AiController.java:158-165、455-485`、`skills/SkillConfigurationService.java:26-31` |
| Swagger/knife4j 与 `org.springframework.ai: DEBUG` 无 profile 区分；`spring.profiles.active: local` 硬编码 | `application.yml:4-5、78-101` |

## 3. 方案设计

### 阶段 1：认证体系（服务端身份建立）

1. 引入 `spring-boot-starter-security` + JWT（无状态，适配 SSE 与桌面端）。
2. Flyway 新增迁移：
   - `app_user`（id, tenant_id, username, password_hash, role, status, created_at）
   - 密码用 BCrypt；role 枚举 `USER` / `ADMIN`。
   - 种子迁移创建初始管理员，密码从环境变量读取（安装包 `.env` 增加 `ADMIN_INIT_PASSWORD`）。
3. 新增 `AuthController`：`POST /auth/register`（可按部署开关关闭注册）、`POST /auth/login`、`POST /auth/refresh`。
4. `SecurityFilterChain`：
   - 放行：`/health`、`/auth/**`、Swagger 仅限 dev profile。
   - 其余全部认证；JWT 解析出 `userId`、`tenantId`、`role` 放入 `Authentication`。
5. 新增 `CurrentActor` 上下文对象（record：userId, tenantId, role），从 `SecurityContext` 解析；Controller 一律取 `CurrentActor`，不再接受任何 tenant 参数。

租户模型简化决策：一期 tenant 跟随用户（注册时分配或管理员创建），不做"用户多租户切换"；避免把租户归属做成又一个客户端可传参数。

### 阶段 2：数据归属改造

1. Flyway 迁移：
   - `love_conversation`、`love_chat_message` 增加 `tenant_id`、`user_id` 列，回填 `'default'`/NULL 后对存量数据标记 `legacy=true`（或统一迁入一个迁移租户），再加 NOT NULL 与索引 `(tenant_id, user_id, updated_at)`。
   - `agent_conversation`、`agent_conversation_message`、`agent_execution_event`、`agent_knowledge_document` 增加 `user_id`（现有 `tenant_id` 保留）。
2. `LoveConversationService`、`AgentConversationService` 所有查询强制带 `tenant_id + user_id` 谓词；`listConversations`、`getMessages`、`deleteConversation`、`saveCompletedTurn` 全部改签名接收 `CurrentActor`。
3. `chatId` 服务端生成 UUID：前端不再传 chatId 创建会话，改为 `POST /love_app/conversations` 返回 id；兼容期对客户端传入的 chatId 校验 UUID 格式 + 归属。
4. 图片：`getLoveImage` 校验 `objectKey` 前缀中的 chatId 属于当前用户，且该 key 出现在该会话消息的 `image_object_keys` 中。
5. Manus 生成文件：目录改为 `FILE_SAVE_DIR/{tenantId}/{userId}/{conversationId}/`（与方案 02 联动），`getManusFile` 校验完整归属链。

### 阶段 3：管理接口与知识库边界

1. `/agent-knowledge/**`、`/love_app/knowledge/**`、`/skills/config`、`/mcp/servers/**` 标注 `hasRole('ADMIN')`。
2. 知识库读取接口按 `CurrentActor.tenantId` 过滤，删除按裸 objectKey 的全库查询；objectKey 解析出的 tenantId 必须与身份一致。
3. Skill/MCP 配置的 `tenantId` 取自身份，接口不再接收。

### 阶段 4：生产配置收敛与验证

1. `application.yml` 移除 `spring.profiles.active: local` 硬编码；prod profile 关闭 springdoc/knife4j 与 `org.springframework.ai` DEBUG。
2. API 兼容：前端 `api/index.js` 统一注入 `Authorization` 头；SSE 的 EventSource 无法带 header → 改用 `fetch` + `ReadableStream` 解析 SSE（前端已有封装点），或后端提供 `GET /sse-ticket` 换取 60 秒一次性 ticket 作为 query 参数。
3. 越权测试矩阵（新增 `AuthOwnershipTest`）：用户 A 访问用户 B 的会话/图片/知识库/文件，全部 404/403；匿名访问全部 401。

## 4. 交付拆分

| 批次 | 内容 | 验收 |
| --- | --- | --- |
| 批次 1 | Spring Security + JWT + 用户表 + 登录注册 | 匿名 401；登录可访问 /health 之外的接口 |
| 批次 2 | 数据归属迁移 + Service/Controller 改造 | 越权矩阵测试全绿 |
| 批次 3 | 管理接口角色 + 知识库/图片边界 | ADMIN 专属接口普通用户 403 |
| 批次 4 | 前端接入 + SSE 鉴权方案 + 配置收敛 | 浏览器全流程 + 桌面端全流程 |

## 5. 风险与决策点

- **存量会话归属**：本地开发数据量小，可直接迁入固定租户；生产若有真实数据需提前决定回填策略。
- **桌面端**：Electron 同源请求，JWT 存应用数据目录即可，无需改造后端。
- **与 ROADMAP 的关系**：本方案落地后，ROADMAP「安全、租户与治理」前两项即完成。
