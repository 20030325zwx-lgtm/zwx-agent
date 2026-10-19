ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS index_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_index_retry
    ON agent_knowledge_document (next_retry_at)
    WHERE lifecycle_status = 'FAILED';
