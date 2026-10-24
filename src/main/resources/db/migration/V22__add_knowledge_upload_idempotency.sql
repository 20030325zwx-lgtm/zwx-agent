-- 上传幂等键：同一 (tenant, agent, key) 只允许一个文档版本，超时重试返回既有文档而不是重复建版
ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_document_idem
    ON agent_knowledge_document (tenant_id, agent_key, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

COMMENT ON COLUMN agent_knowledge_document.idempotency_key IS '上传幂等键：携带同一键的重试返回既有文档（来自 Idempotency-Key 请求头），键不回显';
