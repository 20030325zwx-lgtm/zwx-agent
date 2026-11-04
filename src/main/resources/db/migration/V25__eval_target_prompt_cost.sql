-- ---------------------------------------------------------------------------
-- 评测中台 E2：被测对象（target）一等公民、Prompt 资产库、成本计量、预算与续跑。
-- 设计依据：docs/design/2026-11-04-data-eval-platform-detail-design.md §1
-- 说明：platform schema 由主站 Flyway 拥有；两个中台服务只读校验版本。
-- ---------------------------------------------------------------------------

-- Prompt 资产：可寻址的稳定标识（key），版本发布后不可变
CREATE TABLE platform.prompt_asset (
    id          UUID PRIMARY KEY,
    tenant_id   VARCHAR(64) NOT NULL,
    asset_key   VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.prompt_asset IS 'Prompt 资产（key@version 寻址，补版本四元组的 prompt 维度）';
CREATE UNIQUE INDEX idx_prompt_asset_key ON platform.prompt_asset (tenant_id, asset_key);

CREATE TABLE platform.prompt_version (
    id            UUID PRIMARY KEY,
    tenant_id     VARCHAR(64) NOT NULL,
    asset_id      UUID NOT NULL,
    version_no    INTEGER NOT NULL,
    system        TEXT,
    user_template TEXT,
    params        JSONB NOT NULL DEFAULT '[]'::jsonb,
    notes         TEXT,
    published_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.prompt_version IS 'Prompt 版本（发布后不可变，评测引用 key@versionNo）';
CREATE UNIQUE INDEX idx_prompt_version_no ON platform.prompt_version (asset_id, version_no);

-- 实验：并发度、预算、续跑来源
ALTER TABLE platform.eval_experiment
    ADD COLUMN concurrency INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN budget      JSONB   NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN resume_of   UUID;
COMMENT ON COLUMN platform.eval_experiment.budget IS '预算护栏：{maxCases, maxCost, judgeSampleRate}';
COMMENT ON COLUMN platform.eval_experiment.resume_of IS '续跑来源实验（跳过该实验已完成的用例）';

-- 单例结果：token 与成本
ALTER TABLE platform.eval_case_result
    ADD COLUMN input_tokens  INTEGER,
    ADD COLUMN output_tokens INTEGER,
    ADD COLUMN cost_amount   NUMERIC(12, 6);

-- 用例分级：critical 用于「漏判代价高」的用例（如应走任务却闲聊直达）
ALTER TABLE platform.eval_case
    ADD COLUMN severity VARCHAR(16) NOT NULL DEFAULT 'normal';

-- 事件序号改用全局序列：并发执行时不再依赖 MAX(seq)+1 的读改写
ALTER TABLE platform.eval_event ALTER COLUMN seq TYPE BIGINT;
CREATE SEQUENCE platform.eval_event_seq;
-- 已有事件回填：从全局最大 seq 之后开始发号，避免与历史行冲突
SELECT setval('platform.eval_event_seq',
              COALESCE((SELECT MAX(seq) FROM platform.eval_event), 0) + 1, false);

-- 趋势查询：同一数据集的历次实验
CREATE INDEX idx_eval_experiment_dataset ON platform.eval_experiment (dataset_id, created_at DESC);
