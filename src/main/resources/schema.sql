CREATE TABLE IF NOT EXISTS love_conversation (
    id VARCHAR(64) PRIMARY KEY,
    title VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS love_chat_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES love_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE love_chat_message
    ADD COLUMN IF NOT EXISTS image_object_keys TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];

ALTER TABLE love_chat_message
    ADD COLUMN IF NOT EXISTS knowledge_references JSONB NOT NULL DEFAULT '[]'::JSONB;

ALTER TABLE love_chat_message
    ADD COLUMN IF NOT EXISTS rag_trace JSONB;

ALTER TABLE love_chat_message
    ADD COLUMN IF NOT EXISTS vision_analysis JSONB;

CREATE INDEX IF NOT EXISTS idx_love_conversation_updated_at
    ON love_conversation (updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_love_chat_message_conversation_id
    ON love_chat_message (conversation_id, id);

CREATE TABLE IF NOT EXISTS love_knowledge_index_job (
    id VARCHAR(64) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    document_count INTEGER NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS agent_knowledge_document (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    object_key TEXT NOT NULL,
    filename TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_document_scope
    ON agent_knowledge_document (tenant_id, agent_key, created_at DESC);

CREATE TABLE IF NOT EXISTS agent_conversation (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    title VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_chat_message (
    id BIGSERIAL PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL REFERENCES agent_conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_conversation_scope_updated
    ON agent_conversation (tenant_id, agent_key, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_chat_message_conversation_id
    ON agent_chat_message (conversation_id, id);

ALTER TABLE agent_chat_message
    ADD COLUMN IF NOT EXISTS execution_run_id VARCHAR(64);

CREATE TABLE IF NOT EXISTS agent_execution_event (
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

CREATE INDEX IF NOT EXISTS idx_agent_execution_event_scope
    ON agent_execution_event (tenant_id, agent_key, conversation_id, run_id, sequence);

CREATE TABLE IF NOT EXISTS agent_skill_configuration (
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    skill_id VARCHAR(64) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, agent_key, skill_id)
);
