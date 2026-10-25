-- 索引阶段耗时：供 n8n 待审案件回显「解析 / 切片 / 向量化」三阶段指标
ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS parse_duration_ms  INTEGER,
    ADD COLUMN IF NOT EXISTS vector_duration_ms INTEGER;
