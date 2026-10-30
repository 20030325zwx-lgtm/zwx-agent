-- AI 创作模块 P1 数据契约（C01）。
-- 依据 docs/design/2026-10-29-ai-creative-studio.md §9 与实施手册 §2.2；
-- 归属一致性用 (id, tenant_id, user_id) 组合唯一键 + 外键，禁止跨用户关联落库。

-- ---------------------------------------------------------------- 项目

CREATE TABLE creative_project (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creative_project_identity UNIQUE (id, tenant_id, user_id)
);
CREATE INDEX idx_creative_project_owner ON creative_project (tenant_id, user_id, updated_at DESC, id DESC);

-- ---------------------------------------------------------------- 草稿

CREATE TABLE creative_draft (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    operation VARCHAR(32) NOT NULL,
    original_prompt TEXT NOT NULL DEFAULT '',
    effective_prompt TEXT NOT NULL DEFAULT '',
    reference_asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    model_row_id BIGINT,
    parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_creative_draft_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id)
);
CREATE INDEX idx_creative_draft_owner ON creative_draft (tenant_id, user_id, updated_at DESC, id DESC);
CREATE INDEX idx_creative_draft_project ON creative_draft (project_id, updated_at DESC);

-- ---------------------------------------------------------------- 模型能力档案

CREATE TABLE media_model_profile (
    id UUID PRIMARY KEY,
    model_row_id BIGINT NOT NULL UNIQUE REFERENCES model_provider_model (id),
    adapter_key VARCHAR(64) NOT NULL,
    adapter_version VARCHAR(32) NOT NULL,
    operations JSONB NOT NULL,
    parameter_schema JSONB NOT NULL,
    limits JSONB,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (verification_status IN ('UNVERIFIED', 'VERIFIED', 'FAILED')),
    verified_at TIMESTAMPTZ,
    price_metadata JSONB,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------- 媒体任务

CREATE TABLE media_job (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN ('UI', 'AGENT', 'WORKFLOW', 'TEST')),
    source_run_id VARCHAR(64),
    source_node_id VARCHAR(128),
    parent_job_id UUID,
    operation VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'QUEUED' CHECK (status IN (
        'QUEUED', 'SUBMITTING', 'RECONCILING', 'RUNNING', 'CANCEL_REQUESTED',
        'FETCHING', 'INGEST_FAILED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    input_snapshot JSONB NOT NULL,
    model_snapshot JSONB NOT NULL,
    provider_id BIGINT,
    provider_task_id VARCHAR(128),
    submission_key VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 1,
    lease_owner VARCHAR(64),
    lease_until TIMESTAMPTZ,
    lease_epoch BIGINT NOT NULL DEFAULT 0,
    inflight_reserved BOOLEAN NOT NULL DEFAULT FALSE,
    next_action_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    blocked_reason VARCHAR(64),
    attention_required BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64),
    error_summary VARCHAR(500),
    cancel_requested_at TIMESTAMPTZ,
    observation_deadline_at TIMESTAMPTZ,
    reconcile_after TIMESTAMPTZ,
    manual_resolution VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_media_job_idempotency UNIQUE (tenant_id, user_id, idempotency_key),
    CONSTRAINT uq_media_job_provider_task UNIQUE (provider_id, provider_task_id),
    CONSTRAINT fk_media_job_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id)
);
CREATE INDEX idx_media_job_owner_created ON media_job (tenant_id, user_id, created_at DESC, id DESC);
CREATE INDEX idx_media_job_parent ON media_job (parent_job_id) WHERE parent_job_id IS NOT NULL;
-- 调度器扫描：只看未终态任务
CREATE INDEX idx_media_job_next_action ON media_job (next_action_at)
    WHERE status IN ('QUEUED', 'SUBMITTING', 'RECONCILING', 'RUNNING', 'CANCEL_REQUESTED', 'FETCHING', 'INGEST_FAILED');
-- 非空 provider_task_id 在活跃状态下按供应商唯一（终态后允许同账号新任务复用凭据关联）
CREATE UNIQUE INDEX uq_media_job_active_provider_task ON media_job (provider_id, provider_task_id)
    WHERE provider_task_id IS NOT NULL
      AND status IN ('QUEUED', 'SUBMITTING', 'RECONCILING', 'RUNNING', 'CANCEL_REQUESTED', 'FETCHING', 'INGEST_FAILED');

-- ---------------------------------------------------------------- 尝试记录

CREATE TABLE media_job_attempt (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES media_job (id),
    attempt_no BIGINT NOT NULL,
    action VARCHAR(16) NOT NULL CHECK (action IN ('SUBMIT', 'QUERY', 'CANCEL', 'FETCH')),
    request_id VARCHAR(128),
    outcome VARCHAR(16) NOT NULL CHECK (outcome IN ('ACCEPTED', 'COMPLETED', 'REJECTED', 'UNKNOWN')),
    http_status INT,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    CONSTRAINT uq_media_job_attempt UNIQUE (job_id, attempt_no)
);

-- ---------------------------------------------------------------- 事件流

CREATE TABLE media_job_event (
    seq BIGSERIAL PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES media_job (id),
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    event_type VARCHAR(48) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_media_job_event_job ON media_job_event (job_id, seq);

-- ---------------------------------------------------------------- 产物槽位

CREATE TABLE media_job_output (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES media_job (id),
    output_index INT NOT NULL,
    asset_id UUID NOT NULL,
    ingest_state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (ingest_state IN ('PENDING', 'DONE', 'FAILED')),
    storage_backend VARCHAR(16) NOT NULL CHECK (storage_backend IN ('OSS', 'LOCAL')),
    object_key VARCHAR(500) NOT NULL,
    sha256 VARCHAR(64),
    byte_size BIGINT,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_media_job_output UNIQUE (job_id, output_index),
    CONSTRAINT uq_media_job_output_asset UNIQUE (asset_id)
);

-- ---------------------------------------------------------------- 素材

CREATE TABLE creative_asset (
    id UUID PRIMARY KEY,
    project_id UUID,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    source_job_id UUID,
    output_index INT,
    origin VARCHAR(16) NOT NULL CHECK (origin IN ('UPLOAD', 'GENERATED', 'DERIVED')),
    media_type VARCHAR(32) NOT NULL CHECK (media_type IN ('IMAGE', 'VIDEO', 'OTHER')),
    storage_backend VARCHAR(16) NOT NULL CHECK (storage_backend IN ('OSS', 'LOCAL')),
    object_key VARCHAR(500) NOT NULL,
    mime_type VARCHAR(64) NOT NULL,
    byte_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    width INT,
    height INT,
    duration_ms BIGINT,
    display_name VARCHAR(200) NOT NULL,
    starred BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'READY', 'DELETED')),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_creative_asset_source UNIQUE (source_job_id, output_index)
);
CREATE INDEX idx_creative_asset_owner ON creative_asset (tenant_id, user_id, created_at DESC, id DESC);
CREATE INDEX idx_creative_asset_project ON creative_asset (project_id, created_at DESC, id DESC);

CREATE TABLE creative_asset_link (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    source_asset_id UUID NOT NULL REFERENCES creative_asset (id),
    target_asset_id UUID NOT NULL REFERENCES creative_asset (id),
    job_id UUID,
    relation VARCHAR(24) NOT NULL CHECK (relation IN ('REFERENCE', 'DERIVED_FROM', 'THUMBNAIL_OF')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creative_asset_link UNIQUE (source_asset_id, target_asset_id, relation)
);

-- 任务对素材的引用（输入引用与“在用”判定的事实来源，实施手册 §2.2）
CREATE TABLE media_job_asset_ref (
    job_id UUID NOT NULL REFERENCES media_job (id),
    asset_id UUID NOT NULL REFERENCES creative_asset (id),
    role VARCHAR(24) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, asset_id, role)
);
CREATE INDEX idx_media_job_asset_ref_asset ON media_job_asset_ref (asset_id);

-- ---------------------------------------------------------------- 用量与配额

CREATE TABLE media_usage_record (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL UNIQUE REFERENCES media_job (id),
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    usage_source VARCHAR(32),
    usage_json JSONB,
    estimated_amount NUMERIC(12, 4),
    actual_amount NUMERIC(12, 4),
    currency VARCHAR(8),
    pricing_revision VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE media_provider_quota (
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('GLOBAL', 'PROVIDER', 'USER')),
    scope_id VARCHAR(64) NOT NULL,
    max_inflight INT NOT NULL,
    max_queued INT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (scope_type, scope_id)
);

-- 默认配额（实施手册 C05 默认值，A10 后可调）
INSERT INTO media_provider_quota (scope_type, scope_id, max_inflight, max_queued) VALUES
    ('GLOBAL', '*', 8, NULL),
    ('PROVIDER', '*', 4, NULL)
ON CONFLICT DO NOTHING;
