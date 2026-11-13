-- 知识库文档清洗（规则层）：保留每块的清洗结果与处理记录，并在文档级记录清洗统计。
-- 原文仍在 text_content / html_content / json_content，清洗结果不覆盖原文。

ALTER TABLE agent_knowledge_block
    ADD COLUMN IF NOT EXISTS cleaned_text TEXT,
    ADD COLUMN IF NOT EXISTS cleaning_json JSONB;

COMMENT ON COLUMN agent_knowledge_block.cleaned_text IS '规则清洗后的文本；被清洗丢弃的块也会保留内容以便审计';
COMMENT ON COLUMN agent_knowledge_block.cleaning_json IS '清洗记录：version/actions/dropped/reviewRequired';

ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS cleaning_stats JSONB;

COMMENT ON COLUMN agent_knowledge_document.cleaning_stats IS '清洗统计：块级与切片级的字符数、丢弃原因计数、是否回滚';
