-- ---------------------------------------------------------------------------
-- 评测中台 MVP（E1）：独立 platform schema，与主库只存 ID 不加外键。
-- 设计依据：docs/design/2026-11-03-data-eval-platform-research.md §7.4/§7.5
-- 表命名沿用主库约定（tenant_id + created_at/updated_at + idx_*）。
-- ---------------------------------------------------------------------------

CREATE SCHEMA IF NOT EXISTS platform;

-- 评测数据集：按场景组织，eval_type 决定评估器类型
CREATE TABLE platform.eval_dataset (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    eval_type   VARCHAR(16) NOT NULL CHECK (eval_type IN ('intent', 'retrieval', 'prompt', 'task')),
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.eval_dataset IS '评测数据集（E1 评测中台）';
CREATE UNIQUE INDEX idx_eval_dataset_tenant_name ON platform.eval_dataset (tenant_id, name);

-- 数据集版本：发布后不可变，实验引用版本号；version_no=0 表示草稿
CREATE TABLE platform.eval_dataset_version (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    dataset_id   UUID NOT NULL,
    version_no   INTEGER NOT NULL,
    notes        TEXT,
    published_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.eval_dataset_version IS '评测集发布版本（发布后不可变）';
CREATE UNIQUE INDEX idx_eval_dataset_version ON platform.eval_dataset_version (dataset_id, version_no);

-- 评测用例：payload 按评估器类型存放字段（intent/retrieval/prompt/task 各自 schema）
CREATE TABLE platform.eval_case (
    id           UUID PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    dataset_id   UUID NOT NULL,
    version_no   INTEGER NOT NULL DEFAULT 0,
    external_id  VARCHAR(128) NOT NULL,
    payload      JSONB NOT NULL,
    tags         TEXT[] NOT NULL DEFAULT '{}',
    source       VARCHAR(16) NOT NULL DEFAULT 'manual' CHECK (source IN ('import', 'manual', 'badcase')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.eval_case IS '评测用例（version_no=0 为草稿）';
COMMENT ON COLUMN platform.eval_case.payload IS '用例内容，schema 随 eval_type：intent={question,expectedIntent}；retrieval={question,agentKey,documentId}；prompt={system,input,expect,judge}；task={task,web_search,knowledge,expect}';
CREATE UNIQUE INDEX idx_eval_case_unique ON platform.eval_case (dataset_id, version_no, external_id);
CREATE INDEX idx_eval_case_dataset ON platform.eval_case (dataset_id, version_no);

-- 实验：一次评测运行 = 数据集版本 × 被测对象（candidate 快照）
CREATE TABLE platform.eval_experiment (
    id                  UUID PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    created_by          VARCHAR(64) NOT NULL,
    dataset_id          UUID NOT NULL,
    dataset_version_no  INTEGER NOT NULL,
    eval_type           VARCHAR(16) NOT NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'queued'
                        CHECK (status IN ('queued', 'running', 'succeeded', 'failed', 'cancelled')),
    candidate           JSONB NOT NULL DEFAULT '{}'::jsonb,
    git_commit          VARCHAR(64),
    totals              JSONB,
    error_code          VARCHAR(64),
    error_message       TEXT,
    version             BIGINT NOT NULL DEFAULT 0,
    lease_owner         VARCHAR(64),
    lease_until         TIMESTAMPTZ,
    lease_epoch         BIGINT NOT NULL DEFAULT 0,
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.eval_experiment IS '评测实验（认领围栏仿 media_job：version + lease_epoch）';
COMMENT ON COLUMN platform.eval_experiment.candidate IS '被测对象快照：模型配置（凭据脱敏）、意图路由 provider、备注等';
CREATE INDEX idx_eval_experiment_tenant ON platform.eval_experiment (tenant_id, created_at DESC);
CREATE INDEX idx_eval_experiment_due ON platform.eval_experiment (status, updated_at)
    WHERE status IN ('queued', 'running');

-- 单例结果：passed 为 NULL 表示跳过（如意图路由未启用）；重跑 upsert 幂等
CREATE TABLE platform.eval_case_result (
    id               UUID PRIMARY KEY,
    tenant_id        VARCHAR(64) NOT NULL,
    experiment_id    UUID NOT NULL,
    case_external_id VARCHAR(128) NOT NULL,
    passed           BOOLEAN,
    checks           JSONB NOT NULL DEFAULT '[]'::jsonb,
    judge            JSONB,
    latency_ms       BIGINT,
    answer           TEXT,
    digest           JSONB,
    snapshot         JSONB,
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.eval_case_result IS '单例评测结果（UNIQUE 约束支持重跑幂等 upsert）';
COMMENT ON COLUMN platform.eval_case_result.checks IS '断言明细数组 [{name, passed, detail}]';
COMMENT ON COLUMN platform.eval_case_result.digest IS '运行引用：run_id/conversation_id/token 汇总等轻量指标';
COMMENT ON COLUMN platform.eval_case_result.snapshot IS '轨迹快照（TASK 评估器），超限截断';
CREATE UNIQUE INDEX idx_eval_case_result_unique ON platform.eval_case_result (experiment_id, case_external_id);
CREATE INDEX idx_eval_case_result_experiment ON platform.eval_case_result (experiment_id);

-- 进度事件：轮询游标接口的事实来源，重启不丢
CREATE TABLE platform.eval_event (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id UUID NOT NULL,
    seq           INTEGER NOT NULL,
    type          VARCHAR(32) NOT NULL,
    payload       JSONB,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.eval_event IS '实验进度事件（单调 seq，游标轮询）';
CREATE UNIQUE INDEX idx_eval_event_unique ON platform.eval_event (experiment_id, seq);
