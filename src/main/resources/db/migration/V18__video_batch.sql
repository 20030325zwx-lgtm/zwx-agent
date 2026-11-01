-- 批次生产（P3，2026-11-01）：批次是业务聚合，不是新的执行引擎——子项提交复用
-- ShotProductionService 的候选预建 + MediaJob 受理链路；状态从子项聚合。
-- 唯一意图：一个批次内一个镜头一个候选槽位（UNIQUE(batch_id, shot_id)）；
-- 重试 = 原子项回 PENDING_SUBMIT 且 attempt+1，新候选/新任务，旧候选保留（不覆盖）。

CREATE TABLE video_production_batch (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    scope_label VARCHAR(200) NOT NULL DEFAULT '',
    -- RUNNING：提交中/在途；STOPPING：停止后续提交；STOPPED：有未提交项被停止；
    -- COMPLETED：全部子项终态（含被停止项）
    status VARCHAR(16) NOT NULL DEFAULT 'RUNNING'
        CHECK (status IN ('RUNNING', 'STOPPING', 'STOPPED', 'COMPLETED')),
    concurrency INT NOT NULL DEFAULT 2,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_batch_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id)
);
CREATE INDEX idx_video_batch_project ON video_production_batch (project_id, status);
COMMENT ON TABLE video_production_batch IS '生产批次：业务聚合与停止范围；进度由子项聚合，不复制调度器状态';

CREATE TABLE video_batch_item (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    batch_id UUID NOT NULL REFERENCES video_production_batch (id),
    shot_id UUID NOT NULL REFERENCES video_shot (id),
    -- PENDING_SUBMIT 待提交；SUBMITTED 在途（candidate/job 已挂）；DONE 成功回写；
    -- FAILED 失败（可重试）；SKIPPED 停止时未提交
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING_SUBMIT'
        CHECK (status IN ('PENDING_SUBMIT', 'SUBMITTED', 'DONE', 'FAILED', 'SKIPPED')),
    candidate_id UUID REFERENCES video_shot_candidate (id),
    job_id UUID,
    attempt INT NOT NULL DEFAULT 0,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_item_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_batch_item UNIQUE (batch_id, shot_id)
);
CREATE INDEX idx_video_item_batch ON video_batch_item (batch_id, status);
CREATE INDEX idx_video_item_candidate ON video_batch_item (candidate_id) WHERE candidate_id IS NOT NULL;
COMMENT ON TABLE video_batch_item IS '批次子项：固定镜头 + 候选槽位；停止时 PENDING_SUBMIT→SKIPPED，重试仅对 FAILED';
