-- ============================================================================
-- 基线迁移：整合原 V1~V31 的最终结构（2026-10-28 重建基线）
-- 历史演进（遗留表清理、存量数据回填）不再保留，仅保留最终表结构与注释。
-- 运行时自建对象：agent_knowledge_vector 由 PgVectorStore 启动时创建，不在 Flyway 管理内。
-- 默认知识分类「默认分类」由 AgentKnowledgeDocumentService 运行时创建，无需 seed。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 认证与用户（原 V4）
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

CREATE INDEX idx_app_user_tenant ON app_user (tenant_id);

-- ---------------------------------------------------------------------------
-- 会话与消息（原 V1/V4/V5/V10/V15）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_conversation (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    user_id VARCHAR(64) NOT NULL DEFAULT 'legacy',
    project_id VARCHAR(64),
    title VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_conversation IS '智能体会话；当前仅超级智能体使用';
COMMENT ON COLUMN agent_conversation.id IS '会话唯一标识';
COMMENT ON COLUMN agent_conversation.tenant_id IS '租户标识';
COMMENT ON COLUMN agent_conversation.agent_key IS '智能体标识';
COMMENT ON COLUMN agent_conversation.title IS '会话标题';

CREATE TABLE agent_chat_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'COMPLETED',
    client_request_id VARCHAR(64),
    execution_run_id VARCHAR(64),
    file_attachments JSONB NOT NULL DEFAULT '[]'::jsonb,
    image_object_keys JSONB NOT NULL DEFAULT '[]'::jsonb,
    rag_retrieval_trace JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_chat_message IS '智能体聊天消息';
COMMENT ON COLUMN agent_chat_message.status IS '消息状态，幂等接收期间区分 PENDING/COMPLETED';
COMMENT ON COLUMN agent_chat_message.execution_run_id IS '执行运行标识';
COMMENT ON COLUMN agent_chat_message.file_attachments IS '生成文件附件元数据，JSON 数组';
COMMENT ON COLUMN agent_chat_message.image_object_keys IS '用户消息附带的图片对象存储 key 列表，按 agent/conversation 隔离';
COMMENT ON COLUMN agent_chat_message.rag_retrieval_trace IS '本轮知识检索过程，供质量评测与人工相关性标注';

CREATE UNIQUE INDEX uk_agent_chat_message_client_request
    ON agent_chat_message (client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE INDEX idx_agent_conversation_scope_updated
    ON agent_conversation (tenant_id, agent_key, updated_at DESC);

CREATE INDEX idx_agent_conversation_owner_updated
    ON agent_conversation (tenant_id, user_id, agent_key, updated_at DESC);

CREATE INDEX idx_agent_chat_message_conversation_id
    ON agent_chat_message (conversation_id, id);

CREATE INDEX idx_agent_chat_message_rag_trace
    ON agent_chat_message USING GIN (rag_retrieval_trace);

-- ---------------------------------------------------------------------------
-- 执行事件与技能配置（原 V1）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_execution_event (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL,
    phase VARCHAR(32) NOT NULL,
    summary VARCHAR(255) NOT NULL,
    detail JSONB NOT NULL DEFAULT '{}'::JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, sequence)
);

COMMENT ON TABLE agent_execution_event IS '智能体执行轨迹事件';
COMMENT ON COLUMN agent_execution_event.sequence IS '同一次执行中的事件序号';
COMMENT ON COLUMN agent_execution_event.phase IS '执行阶段';
COMMENT ON COLUMN agent_execution_event.summary IS '事件摘要';
COMMENT ON COLUMN agent_execution_event.detail IS '事件详细数据，JSON 对象';

CREATE INDEX idx_agent_execution_event_scope
    ON agent_execution_event (tenant_id, agent_key, conversation_id, run_id, sequence);

CREATE TABLE agent_skill_configuration (
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, agent_key, skill_id)
);

COMMENT ON TABLE agent_skill_configuration IS '智能体技能配置';
COMMENT ON COLUMN agent_skill_configuration.enabled IS '是否启用';

-- ---------------------------------------------------------------------------
-- 知识库：文档（最终结构，含 V6 版本治理 / V9 重试 / V14 分类 / V20~V24 治理列）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_knowledge_document (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    object_key TEXT NOT NULL,
    filename TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    category_id VARCHAR(64) NOT NULL,
    logical_key VARCHAR(255) NOT NULL,
    version_no INTEGER NOT NULL,
    lifecycle_status VARCHAR(16) NOT NULL,
    content_sha256 CHAR(64),
    effective_from TIMESTAMPTZ,
    effective_to TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    archived_at TIMESTAMPTZ,
    supersedes_document_id VARCHAR(64),
    created_by VARCHAR(64),
    conflict_reason TEXT,
    index_attempts INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    auto_publish BOOLEAN NOT NULL DEFAULT TRUE,
    review_note TEXT,
    idempotency_key VARCHAR(64),
    resume_webhook_url TEXT,
    resume_expires_at TIMESTAMPTZ,
    parse_duration_ms INTEGER,
    vector_duration_ms INTEGER,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

COMMENT ON TABLE agent_knowledge_document IS '智能体知识库文档';
COMMENT ON COLUMN agent_knowledge_document.status IS '索引状态';
COMMENT ON COLUMN agent_knowledge_document.logical_key IS '逻辑文档标识：同一份业务资料的稳定身份（小写、去扩展名文件名兜底）';
COMMENT ON COLUMN agent_knowledge_document.version_no IS '同一逻辑文档内的版本号，从 1 递增，服务端生成';
COMMENT ON COLUMN agent_knowledge_document.lifecycle_status IS '发布生命周期：UPLOADING/INDEXING/READY/ACTIVE/ARCHIVED/FAILED/CONFLICT，仅 ACTIVE 参与检索';
COMMENT ON COLUMN agent_knowledge_document.content_sha256 IS '原始文件 SHA-256，用于重复内容检测';
COMMENT ON COLUMN agent_knowledge_document.effective_from IS '生效开始时间';
COMMENT ON COLUMN agent_knowledge_document.effective_to IS '生效结束时间（归档时填写）';
COMMENT ON COLUMN agent_knowledge_document.published_at IS '发布时间';
COMMENT ON COLUMN agent_knowledge_document.archived_at IS '归档时间';
COMMENT ON COLUMN agent_knowledge_document.supersedes_document_id IS '被本版本替代的上一版本文档 ID';
COMMENT ON COLUMN agent_knowledge_document.created_by IS '上传者用户名';
COMMENT ON COLUMN agent_knowledge_document.conflict_reason IS '冲突原因说明';
COMMENT ON COLUMN agent_knowledge_document.auto_publish IS '索引成功后是否自动发布；工作流人工确认场景设为 FALSE';
COMMENT ON COLUMN agent_knowledge_document.review_note IS '工作流人工审核意见';
COMMENT ON COLUMN agent_knowledge_document.idempotency_key IS '上传幂等键：携带同一键的重试返回既有文档（来自 Idempotency-Key 请求头），键不回显';
COMMENT ON COLUMN agent_knowledge_document.resume_webhook_url IS 'n8n 人工审核 Wait 恢复 URL；机密，仅后端持有，不进任何 API 响应';
COMMENT ON COLUMN agent_knowledge_document.resume_expires_at IS '恢复 URL 的预期失效时间（可空）';
COMMENT ON COLUMN agent_knowledge_document.parse_duration_ms IS '解析阶段耗时（毫秒）';
COMMENT ON COLUMN agent_knowledge_document.vector_duration_ms IS '向量化阶段耗时（毫秒）';

CREATE INDEX idx_agent_knowledge_document_scope
    ON agent_knowledge_document (tenant_id, agent_key, created_at DESC);

CREATE UNIQUE INDEX uk_agent_knowledge_logical_version
    ON agent_knowledge_document (tenant_id, agent_key, logical_key, version_no);

CREATE UNIQUE INDEX uk_agent_knowledge_active_version
    ON agent_knowledge_document (tenant_id, agent_key, logical_key)
    WHERE lifecycle_status = 'ACTIVE';

CREATE INDEX idx_agent_knowledge_retrieval_scope
    ON agent_knowledge_document (tenant_id, agent_key, lifecycle_status);

CREATE INDEX idx_agent_knowledge_content_hash
    ON agent_knowledge_document (tenant_id, agent_key, content_sha256);

CREATE INDEX idx_agent_knowledge_index_retry
    ON agent_knowledge_document (next_retry_at)
    WHERE lifecycle_status = 'FAILED';

CREATE UNIQUE INDEX uk_agent_knowledge_document_idem
    ON agent_knowledge_document (tenant_id, agent_key, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 知识库：分类（原 V14，文档分类 NOT NULL + 外键）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_knowledge_category (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64)
);

COMMENT ON TABLE agent_knowledge_category IS '知识库文档分类，按租户与智能体隔离';

CREATE UNIQUE INDEX uk_agent_knowledge_category_name
    ON agent_knowledge_category (tenant_id, agent_key, name);

CREATE INDEX idx_agent_knowledge_category_scope
    ON agent_knowledge_category (tenant_id, agent_key, created_at);

ALTER TABLE agent_knowledge_document
    ADD CONSTRAINT fk_agent_knowledge_document_category
        FOREIGN KEY (category_id) REFERENCES agent_knowledge_category (id) ON DELETE RESTRICT;

CREATE INDEX idx_agent_knowledge_document_category
    ON agent_knowledge_document (tenant_id, agent_key, category_id, created_at DESC);

COMMENT ON COLUMN agent_knowledge_document.category_id IS '文档所属分类';

-- ---------------------------------------------------------------------------
-- 知识库：来源块（原 V19）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_knowledge_block (
    id               VARCHAR(64) PRIMARY KEY,
    document_id      VARCHAR(64) NOT NULL,
    tenant_id        VARCHAR(64) NOT NULL,
    agent_key        VARCHAR(32) NOT NULL,
    block_id         VARCHAR(128) NOT NULL,
    source_type      VARCHAR(16) NOT NULL,
    page_number      INTEGER,
    bbox             JSONB,
    caption          TEXT,
    text_content     TEXT,
    html_content     TEXT,
    json_content     JSONB,
    image_object_key VARCHAR(512),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, block_id)
);

COMMENT ON TABLE agent_knowledge_block IS '知识库来源块：保存表格/图片/文本的原始区域，用于引用回看';
COMMENT ON COLUMN agent_knowledge_block.block_id IS '解析结果内的稳定块标识，同一文档版本内唯一';
COMMENT ON COLUMN agent_knowledge_block.source_type IS 'TEXT / TABLE / FIGURE / EQUATION';
COMMENT ON COLUMN agent_knowledge_block.bbox IS '页面内归一化坐标 [x0,y0,x1,y1]';
COMMENT ON COLUMN agent_knowledge_block.json_content IS '结构化内容，例如表格 headers/rows';

CREATE INDEX idx_knowledge_block_scope
    ON agent_knowledge_block (tenant_id, agent_key, document_id, source_type);

-- ---------------------------------------------------------------------------
-- 知识库：审批治理（原 V23）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_reviewer (
    id          VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    agent_key   VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    assigned_by VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_reviewer UNIQUE (tenant_id, agent_key, user_id)
);

COMMENT ON TABLE agent_reviewer IS '审批人分配：角色决定能否成为审批人，本表决定可审批的 (tenant, agent) 范围';

CREATE INDEX idx_agent_reviewer_user ON agent_reviewer (user_id);

CREATE TABLE agent_knowledge_review_log (
    id               VARCHAR(64) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL,
    agent_key        VARCHAR(64) NOT NULL,
    document_id      VARCHAR(64) NOT NULL,
    action           VARCHAR(24) NOT NULL,
    actor_user_id    VARCHAR(64),
    actor_username   VARCHAR(64),
    actor_role       VARCHAR(16),
    channel          VARCHAR(16) NOT NULL,
    note             TEXT,
    n8n_execution_id VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_knowledge_review_log IS '知识文档审批审计：只追加，应用层不提供修改与删除';

CREATE INDEX idx_review_log_document
    ON agent_knowledge_review_log (document_id, created_at);

-- ---------------------------------------------------------------------------
-- 长期记忆（原 V7）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_memory_fact (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    user_id VARCHAR(64),
    fact TEXT NOT NULL,
    fact_hash CHAR(64) NOT NULL,
    source_conversation_id VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_memory_fact IS '长期记忆事实日志：跨会话事实，按 (tenant, agent, fact_hash) 去重，冲突更新 last_seen_at';

CREATE UNIQUE INDEX uk_agent_memory_fact ON agent_memory_fact (tenant_id, agent_key, fact_hash);
CREATE INDEX idx_agent_memory_scope ON agent_memory_fact (tenant_id, agent_key, created_at DESC);

-- ---------------------------------------------------------------------------
-- 执行租约与实例心跳（原 V8/V12）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_conversation_lock (
    conversation_id VARCHAR(160) PRIMARY KEY,
    owner_token VARCHAR(36) NOT NULL,
    owner_instance VARCHAR(36),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_conversation_lock IS '会话级 Agent 执行租约锁，支持多实例互斥并允许过期回收';

CREATE INDEX idx_agent_conversation_lock_expiry
    ON agent_conversation_lock (expires_at);

CREATE TABLE agent_instance (
    instance_id VARCHAR(36) PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_instance IS '服务实例心跳，用于区分存活实例与崩溃实例留下的租约';

CREATE INDEX idx_agent_instance_heartbeat
    ON agent_instance (heartbeat_at DESC);

-- ---------------------------------------------------------------------------
-- 超级智能体 run（最终结构：V11 检查点 + V12 实例 + V25 token + V26 确认 + V31 返工）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_run (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    user_message_id BIGINT NOT NULL REFERENCES agent_chat_message(id) ON DELETE CASCADE,
    assistant_message_id BIGINT REFERENCES agent_chat_message(id) ON DELETE SET NULL,
    status VARCHAR(16) NOT NULL,
    input TEXT NOT NULL,
    history_context TEXT,
    knowledge_context TEXT,
    rag_trace_json TEXT,
    last_node VARCHAR(32),
    next_node VARCHAR(32),
    plan JSONB,
    task_results JSONB NOT NULL DEFAULT '[]'::jsonb,
    activities JSONB NOT NULL DEFAULT '[]'::jsonb,
    sse_events JSONB NOT NULL DEFAULT '[]'::jsonb,
    revision INTEGER NOT NULL DEFAULT 0,
    verification_status VARCHAR(16),
    verification_feedback TEXT,
    final_answer TEXT,
    partial_answer TEXT,
    error_message TEXT,
    resume_count INTEGER NOT NULL DEFAULT 0,
    owner_instance VARCHAR(36),
    pending_confirmation JSONB,
    prompt_tokens     INTEGER DEFAULT 0,
    completion_tokens INTEGER DEFAULT 0,
    llm_calls         INTEGER DEFAULT 0,
    token_usage       JSONB,
    revise_steps JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_run IS '超级智能体图执行实例与可恢复检查点';
COMMENT ON COLUMN agent_run.last_node IS '最近一个已完成的图节点';
COMMENT ON COLUMN agent_run.next_node IS '恢复时应继续执行的图节点';
COMMENT ON COLUMN agent_run.pending_confirmation IS 'run 暂停等待确认时的待确认工具列表（JSON），恢复执行时清空';
COMMENT ON COLUMN agent_run.llm_calls IS '本次 run 的模型调用次数（worker 每步各一次，非节点数）';
COMMENT ON COLUMN agent_run.token_usage IS '按阶段的 token 与提示词构成分解（JSON）';
COMMENT ON COLUMN agent_run.revise_steps IS '质检点名需要返工的子任务序号（0 起，对齐计划顺序；空数组表示全部重做）';

CREATE INDEX idx_agent_run_conversation
    ON agent_run (conversation_id, created_at DESC);

CREATE INDEX idx_agent_run_status
    ON agent_run (status, updated_at DESC);

CREATE INDEX idx_agent_run_owner
    ON agent_run (owner_instance, status);

CREATE INDEX idx_agent_run_created_prompt_tokens
    ON agent_run (created_at, prompt_tokens);

-- ---------------------------------------------------------------------------
-- 工具调用幂等账本（原 V13）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_tool_call (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    tool_name VARCHAR(128) NOT NULL,
    arguments_hash VARCHAR(64) NOT NULL,
    arguments TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    result TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, tool_name, arguments_hash)
);

COMMENT ON TABLE agent_tool_call IS '超级智能体工具调用幂等账本；恢复时复用成功结果或阻止未知副作用重放';
COMMENT ON COLUMN agent_tool_call.status IS 'PENDING | SUCCEEDED | FAILED | UNKNOWN';

CREATE INDEX idx_agent_tool_call_run
    ON agent_tool_call (run_id, id);

-- ---------------------------------------------------------------------------
-- 工具副作用人工确认（原 V26）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_tool_confirmation (
    id                BIGSERIAL PRIMARY KEY,
    run_id            VARCHAR(64)  NOT NULL,
    tool_name         VARCHAR(255) NOT NULL,
    arguments_summary TEXT,
    decision          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    decided_at        TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_tool_confirmation UNIQUE (run_id, tool_name)
);

COMMENT ON TABLE agent_tool_confirmation IS '有副作用工具的人工确认台账：PENDING/APPROVED/DENIED，批准后同一 run 内放行';
COMMENT ON COLUMN agent_tool_confirmation.arguments_summary IS '参数摘要（键名与字符数），不含完整参数值，避免敏感信息入库';

CREATE INDEX idx_agent_tool_confirmation_run ON agent_tool_confirmation (run_id);

-- ---------------------------------------------------------------------------
-- 项目工作区（原 V27/V28/V29 最终结构）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_project (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    owner_user_id VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    directory_name VARCHAR(128) NOT NULL,
    path_scope VARCHAR(16) NOT NULL DEFAULT 'ROOT',
    workspace_path VARCHAR(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_project_owner_path
        UNIQUE (tenant_id, owner_user_id, path_scope, workspace_path),
    CONSTRAINT ck_agent_project_path_scope
        CHECK (path_scope IN ('ROOT', 'LOCAL'))
);

CREATE INDEX idx_agent_project_owner_updated
    ON agent_project (tenant_id, owner_user_id, updated_at DESC);

ALTER TABLE agent_conversation
    ADD CONSTRAINT fk_agent_conversation_project
        FOREIGN KEY (project_id) REFERENCES agent_project(id) ON DELETE SET NULL;

CREATE INDEX idx_agent_conversation_project_updated
    ON agent_conversation (project_id, updated_at DESC);

CREATE TABLE agent_project_write_lock (
    project_id VARCHAR(64) PRIMARY KEY REFERENCES agent_project(id) ON DELETE CASCADE,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE agent_project_write_lock IS 'DIRECT 模式项目单写者租约，避免并发 run 修改同一源码目录';

CREATE INDEX idx_agent_project_write_lock_expiry
    ON agent_project_write_lock (expires_at);

-- ---------------------------------------------------------------------------
-- Run 固化 checkout（原 V30）
-- ---------------------------------------------------------------------------
CREATE TABLE agent_run_workspace (
    run_id VARCHAR(64) PRIMARY KEY REFERENCES agent_run(id) ON DELETE CASCADE,
    project_id VARCHAR(64) NOT NULL REFERENCES agent_project(id) ON DELETE RESTRICT,
    mode VARCHAR(16) NOT NULL,
    checkout_path VARCHAR(2048) NOT NULL,
    branch_name VARCHAR(255),
    base_head VARCHAR(64),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_run_workspace_mode CHECK (mode IN ('DIRECT', 'WORKTREE')),
    CONSTRAINT ck_agent_run_workspace_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'INTERRUPTED', 'FAILED'))
);

COMMENT ON TABLE agent_run_workspace IS 'Run 固化的源码 checkout；Git 项目默认使用受管 worktree，恢复时复用';

CREATE INDEX idx_agent_run_workspace_project
    ON agent_run_workspace (project_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- MCP 服务配置（原 V3/V18 最终结构）
-- ---------------------------------------------------------------------------
CREATE TABLE mcp_server_configuration (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auth_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
    auth_header_name VARCHAR(128),
    secret_ciphertext TEXT,
    secret_hint VARCHAR(32),
    allowed_tools JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, name),
    CONSTRAINT chk_mcp_auth_type CHECK (auth_type IN ('NONE', 'BEARER', 'HEADER'))
);

COMMENT ON TABLE mcp_server_configuration IS 'Tenant-scoped remote MCP SSE server configuration';
COMMENT ON COLUMN mcp_server_configuration.endpoint IS 'MCP SSE server base URL; stdio commands are intentionally unsupported';
COMMENT ON COLUMN mcp_server_configuration.allowed_tools IS 'JSON array of explicitly authorized MCP tool names; NULL preserves legacy allow-all behavior';
COMMENT ON COLUMN mcp_server_configuration.secret_ciphertext IS 'AES-GCM encrypted MCP authentication secret';

CREATE INDEX idx_mcp_server_configuration_tenant ON mcp_server_configuration (tenant_id, enabled);

-- ---------------------------------------------------------------------------
-- 应用配置（原 V16）
-- ---------------------------------------------------------------------------
CREATE TABLE app_configuration (
    id BIGSERIAL PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    owner_id VARCHAR(64) NOT NULL DEFAULT '',
    config_type VARCHAR(16) NOT NULL,
    config_key VARCHAR(64) NOT NULL,
    provider VARCHAR(32),
    base_url VARCHAR(512),
    model_name VARCHAR(128),
    secret_ciphertext TEXT,
    secret_hint VARCHAR(32),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    extra JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_app_configuration_scope CHECK (scope_type IN ('SYSTEM', 'USER')),
    CONSTRAINT chk_app_configuration_type CHECK (config_type IN ('MODEL', 'SERVICE'))
);

COMMENT ON TABLE app_configuration IS '应用配置：用户/系统级模型与外部服务覆盖项，密钥使用 AES-GCM 加密';
COMMENT ON COLUMN app_configuration.scope_type IS 'SYSTEM=系统默认，USER=用户个人覆盖';
COMMENT ON COLUMN app_configuration.owner_id IS 'USER 作用域的用户 ID；SYSTEM 作用域为空字符串';
COMMENT ON COLUMN app_configuration.config_key IS '配置项标识，例如 chat/vision/docling';
COMMENT ON COLUMN app_configuration.secret_ciphertext IS 'AES-GCM 加密后的密钥，禁止明文落库';
COMMENT ON COLUMN app_configuration.secret_hint IS '密钥末位提示，用于前端掩码展示';

CREATE UNIQUE INDEX uk_app_configuration_scope_key
    ON app_configuration (scope_type, owner_id, config_type, config_key);

CREATE INDEX idx_app_configuration_owner
    ON app_configuration (scope_type, owner_id, config_type);
