-- 项目创作画布当前草稿；运行快照继续由 workflow_run / media_job 承载。
CREATE TABLE creative_canvas (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    graph JSONB NOT NULL DEFAULT '{"nodes":[],"edges":[]}'::jsonb,
    viewport JSONB NOT NULL DEFAULT '{"x":0,"y":0,"zoom":1}'::jsonb,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creative_canvas_project UNIQUE (project_id),
    CONSTRAINT fk_creative_canvas_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id)
);

CREATE INDEX idx_creative_canvas_owner ON creative_canvas (tenant_id, user_id, updated_at DESC);
