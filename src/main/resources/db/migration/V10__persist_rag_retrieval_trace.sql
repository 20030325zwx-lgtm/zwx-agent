ALTER TABLE agent_chat_message
    ADD COLUMN IF NOT EXISTS rag_retrieval_trace JSONB NOT NULL DEFAULT '{}'::JSONB;

COMMENT ON COLUMN agent_chat_message.rag_retrieval_trace IS '本轮知识检索过程，供质量评测与人工相关性标注';

CREATE INDEX IF NOT EXISTS idx_agent_chat_message_rag_trace
    ON agent_chat_message USING GIN (rag_retrieval_trace);
