-- 创作 Skill 模块 P0 最小数据契约（docs/design/2026-11-02-video-skill-module.md §8.3）。
-- skill_definition/skill_version/skill_run 对应文档提案的 skill_definition / skill_version / skill_run；
-- MVP 中一个 run 只关联一个 media_job（confirm 时落 media_job_id），完整 run_step/multi-job 关联留待 P1。
-- 技能正文全部为本项目自行编写（SELF_WRITTEN），不复制参考站内容。

-- ---------------------------------------------------------------- 技能定义

CREATE TABLE creative_skill (
    id UUID PRIMARY KEY,
    slug VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(80) NOT NULL,
    summary VARCHAR(300) NOT NULL DEFAULT '',
    category VARCHAR(32) NOT NULL DEFAULT 'GENERAL',
    stage VARCHAR(32) NOT NULL DEFAULT 'PROMPT',
    input_types VARCHAR(64) NOT NULL DEFAULT 'TEXT',
    output_types VARCHAR(64) NOT NULL DEFAULT 'TEXT',
    required_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    visibility VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' CHECK (visibility IN ('SYSTEM', 'PRIVATE', 'PUBLIC')),
    owner_user_id VARCHAR(64),
    lifecycle VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED' CHECK (lifecycle IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    latest_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_creative_skill_browse ON creative_skill (lifecycle, category, slug);

-- ---------------------------------------------------------------- 不可变技能版本

CREATE TABLE creative_skill_version (
    id UUID PRIMARY KEY,
    skill_id UUID NOT NULL REFERENCES creative_skill (id),
    version INTEGER NOT NULL,
    body TEXT NOT NULL,
    input_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    source_type VARCHAR(16) NOT NULL DEFAULT 'BUILTIN' CHECK (source_type IN ('BUILTIN', 'MARKDOWN', 'ZIP', 'GITHUB')),
    source_ref VARCHAR(200) NOT NULL DEFAULT '',
    content_hash VARCHAR(64) NOT NULL,
    license_label VARCHAR(120) NOT NULL DEFAULT 'SELF_WRITTEN',
    license_status VARCHAR(16) NOT NULL DEFAULT 'OWN' CHECK (license_status IN ('OWN', 'PENDING', 'CLEARED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_creative_skill_version UNIQUE (skill_id, version)
);

-- ---------------------------------------------------------------- 技能运行（方案 + 确认 + 任务关联）

CREATE TABLE creative_skill_run (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    skill_id UUID NOT NULL,
    skill_version_id UUID NOT NULL,
    input_snapshot JSONB NOT NULL,
    plan JSONB,
    plan_status VARCHAR(24) NOT NULL DEFAULT 'PENDING_CONFIRM'
        CHECK (plan_status IN ('PENDING_CONFIRM', 'CONFIRMED', 'FAILED')),
    media_job_id UUID,
    fail_reason VARCHAR(500) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_creative_skill_run_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id)
);
CREATE INDEX idx_creative_skill_run_owner ON creative_skill_run (tenant_id, user_id, created_at DESC, id DESC);
CREATE INDEX idx_creative_skill_run_project ON creative_skill_run (project_id, created_at DESC);
