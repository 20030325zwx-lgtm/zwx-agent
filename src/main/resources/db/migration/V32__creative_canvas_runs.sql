-- 项目画布运行归属、幂等提交与人工候选选择。
ALTER TABLE workflow_run DROP CONSTRAINT IF EXISTS ck_workflow_run_status;
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_status
    CHECK (status IN ('RUNNING', 'WAITING_EXTERNAL', 'WAITING_SELECTION', 'SUCCEEDED', 'FAILED', 'CANCELLED'));

ALTER TABLE workflow_node_execution ADD COLUMN IF NOT EXISTS candidate_assets JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE workflow_node_execution DROP CONSTRAINT IF EXISTS ck_workflow_node_execution_status;
ALTER TABLE workflow_node_execution ADD CONSTRAINT ck_workflow_node_execution_status
    CHECK (status IN ('WAITING_EXTERNAL', 'WAITING_SELECTION', 'SUCCEEDED', 'FAILED'));

CREATE TABLE creative_canvas_run (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    canvas_revision BIGINT NOT NULL,
    run_scope VARCHAR(16) NOT NULL,
    from_node_id VARCHAR(128),
    idempotency_key VARCHAR(128) NOT NULL,
    workflow_run_id VARCHAR(36),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creative_canvas_run_idempotency UNIQUE (tenant_id, user_id, idempotency_key),
    CONSTRAINT uq_creative_canvas_run_workflow UNIQUE (workflow_run_id),
    CONSTRAINT ck_creative_canvas_run_scope CHECK (run_scope IN ('node', 'from_here', 'all')),
    CONSTRAINT fk_creative_canvas_run_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT fk_creative_canvas_run_workflow FOREIGN KEY (workflow_run_id) REFERENCES workflow_run (id)
);

CREATE INDEX idx_creative_canvas_run_project
    ON creative_canvas_run (tenant_id, user_id, project_id, created_at DESC);
