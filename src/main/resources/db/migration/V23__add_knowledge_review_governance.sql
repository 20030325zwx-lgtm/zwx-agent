-- 审批治理：审批人分配、审计时间线、n8n 恢复凭据登记
-- 设计：docs/design/2026-10-24-knowledge-approval-entry.md

CREATE TABLE IF NOT EXISTS agent_reviewer (
    id          VARCHAR(64) PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL,
    agent_key   VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    assigned_by VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_agent_reviewer UNIQUE (tenant_id, agent_key, user_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_reviewer_user ON agent_reviewer (user_id);

COMMENT ON TABLE agent_reviewer IS '审批人分配：角色决定能否成为审批人，本表决定可审批的 (tenant, agent) 范围';

CREATE TABLE IF NOT EXISTS agent_knowledge_review_log (
    id               VARCHAR(64) PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL,
    agent_key        VARCHAR(64) NOT NULL,
    document_id      VARCHAR(64) NOT NULL,
    action           VARCHAR(24) NOT NULL,
    actor_user_id    VARCHAR(64),
    actor_username   VARCHAR(64),
    actor_role       VARCHAR(16),
    channel          VARCHAR(16) NOT NULL,
    note             TEXT,
    n8n_execution_id VARCHAR(64),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_review_log_document
    ON agent_knowledge_review_log (document_id, created_at);

COMMENT ON TABLE agent_knowledge_review_log IS '知识文档审批审计：只追加，应用层不提供修改与删除';

ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS resume_webhook_url TEXT,
    ADD COLUMN IF NOT EXISTS resume_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN agent_knowledge_document.resume_webhook_url IS 'n8n 人工审核 Wait 恢复 URL；机密，仅后端持有，不进任何 API 响应';
COMMENT ON COLUMN agent_knowledge_document.resume_expires_at IS '恢复 URL 的预期失效时间（可空）';
