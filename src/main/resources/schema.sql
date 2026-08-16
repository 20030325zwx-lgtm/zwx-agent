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

CREATE INDEX IF NOT EXISTS idx_love_conversation_updated_at
    ON love_conversation (updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_love_chat_message_conversation_id
    ON love_chat_message (conversation_id, id);
