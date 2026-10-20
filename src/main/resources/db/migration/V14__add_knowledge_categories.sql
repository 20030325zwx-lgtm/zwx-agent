-- 知识库文档分类：每个租户/智能体拥有独立分类，已有文档统一归入“默认分类”。
CREATE TABLE IF NOT EXISTS agent_knowledge_category (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    agent_key VARCHAR(32) NOT NULL,
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(64)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_category_name
    ON agent_knowledge_category (tenant_id, agent_key, name);

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_category_scope
    ON agent_knowledge_category (tenant_id, agent_key, created_at);

ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS category_id VARCHAR(64);

-- 为已经存在文档的租户/智能体补默认分类。
INSERT INTO agent_knowledge_category (id, tenant_id, agent_key, name, created_by)
SELECT 'cat-' || substr(md5(tenant_id || ':' || agent_key || ':default'), 1, 28),
       tenant_id, agent_key, '默认分类', 'system'
FROM (SELECT DISTINCT tenant_id, agent_key FROM agent_knowledge_document) scopes
ON CONFLICT (tenant_id, agent_key, name) DO NOTHING;

-- 已有文档全部转移到默认分类。
UPDATE agent_knowledge_document d
SET category_id = c.id
FROM agent_knowledge_category c
WHERE d.category_id IS NULL
  AND c.tenant_id = d.tenant_id
  AND c.agent_key = d.agent_key
  AND c.name = '默认分类';

ALTER TABLE agent_knowledge_document
    ALTER COLUMN category_id SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_agent_knowledge_document_category'
    ) THEN
        ALTER TABLE agent_knowledge_document
            ADD CONSTRAINT fk_agent_knowledge_document_category
            FOREIGN KEY (category_id) REFERENCES agent_knowledge_category (id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_document_category
    ON agent_knowledge_document (tenant_id, agent_key, category_id, created_at DESC);

COMMENT ON TABLE agent_knowledge_category IS '知识库文档分类，按租户与智能体隔离';
COMMENT ON COLUMN agent_knowledge_document.category_id IS '文档所属分类；已有文档在 V14 中统一迁入默认分类';
