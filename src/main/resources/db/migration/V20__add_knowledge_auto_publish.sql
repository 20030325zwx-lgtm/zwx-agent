ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS auto_publish BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN agent_knowledge_document.auto_publish IS '索引成功后是否自动发布；工作流人工确认场景设为 FALSE';
