-- 最小成片闭环（2026-11-06，P2 制作视图）：
-- DRAFT 行是可编辑草稿（片段顺序/移除）；export 从草稿快照出一条新的运行行（幂等键防重复提交），
-- 状态机 PREPARING → PROCESSING → UPLOADING → COMPLETED / FAILED / CANCELLED，失败可重试。
-- 片段冻结在 items JSONB：导出期间继续编辑项目不改变在途成片。
CREATE TABLE video_final_cut (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PREPARING', 'PROCESSING', 'UPLOADING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    items JSONB NOT NULL DEFAULT '[]'::jsonb,
    items_version BIGINT NOT NULL DEFAULT 1,
    output_asset_id UUID,
    output_bytes BIGINT,
    output_duration_ms BIGINT,
    output_size VARCHAR(16) NOT NULL DEFAULT '',
    error TEXT NOT NULL DEFAULT '',
    source_draft_id UUID,
    idempotency_key VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_final_cut_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_final_cut_idempotency UNIQUE (tenant_id, user_id, idempotency_key)
);
CREATE INDEX idx_video_final_cut_project
    ON video_final_cut (project_id, tenant_id, user_id, created_at DESC);
COMMENT ON TABLE video_final_cut IS '成片：草稿（DRAFT）与导出运行快照分离；产物登记为 DERIVED 素材，下载走统一素材链路';
