ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS review_note TEXT;

COMMENT ON COLUMN agent_knowledge_document.review_note IS '工作流人工审核意见';
