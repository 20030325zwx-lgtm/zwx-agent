-- 技能导入（P0-C MVP）与逐镜运行关联（P1 MVP）。
-- 导入默认 PRIVATE，license_status=PENDING：外部内容授权未核实前不进入系统目录。
-- run 与 media_job 的一对多关联：每个镜头一个独立 MediaJob，失败可原位重试（复用现有 retry）。

-- ---------------------------------------------------------------- 导入记录

CREATE TABLE creative_skill_import (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    source_type VARCHAR(16) NOT NULL DEFAULT 'MARKDOWN' CHECK (source_type IN ('MARKDOWN', 'ZIP', 'GITHUB')),
    source_ref VARCHAR(200) NOT NULL DEFAULT '',
    raw_hash VARCHAR(64) NOT NULL,
    parsed JSONB,
    status VARCHAR(16) NOT NULL DEFAULT 'PARSED' CHECK (status IN ('PARSED', 'CONFIRMED', 'FAILED')),
    skill_id UUID,
    error VARCHAR(500) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_creative_skill_import_owner ON creative_skill_import (tenant_id, user_id, created_at DESC);

-- ---------------------------------------------------------------- run 逐镜任务关联

CREATE TABLE creative_skill_run_job (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES creative_skill_run (id),
    job_id UUID NOT NULL,
    shot_index INTEGER NOT NULL DEFAULT 0,
    shot_title VARCHAR(120) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creative_skill_run_job UNIQUE (run_id, job_id)
);
CREATE INDEX idx_creative_skill_run_job_run ON creative_skill_run_job (run_id, shot_index);
