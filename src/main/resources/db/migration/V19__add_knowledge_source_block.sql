-- 知识库来源块：切片用于检索，来源块用于回答引用定位。
CREATE TABLE IF NOT EXISTS agent_knowledge_block (
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

CREATE INDEX IF NOT EXISTS idx_knowledge_block_scope
    ON agent_knowledge_block (tenant_id, agent_key, document_id, source_type);

COMMENT ON TABLE agent_knowledge_block IS '知识库来源块：保存表格/图片/文本的原始区域，用于引用回看';
COMMENT ON COLUMN agent_knowledge_block.block_id IS '解析结果内的稳定块标识，同一文档版本内唯一';
COMMENT ON COLUMN agent_knowledge_block.source_type IS 'TEXT / TABLE / FIGURE / EQUATION';
COMMENT ON COLUMN agent_knowledge_block.bbox IS '页面内归一化坐标 [x0,y0,x1,y1]';
COMMENT ON COLUMN agent_knowledge_block.json_content IS '结构化内容，例如表格 headers/rows';
