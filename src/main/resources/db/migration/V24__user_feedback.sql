-- ---------------------------------------------------------------------------
-- 数据中台 D1 MVP：用户反馈与 BadCase 库（platform schema）。
-- 反馈一次点击入库；负面反馈即 BadCase；「一键回流」把反馈转成评测用例
-- （platform.eval_case，source=badcase）并回写 converted_* 链接。
-- ---------------------------------------------------------------------------

CREATE TABLE platform.user_feedback (
    id                   UUID PRIMARY KEY,
    tenant_id            VARCHAR(64) NOT NULL,
    user_id              VARCHAR(64) NOT NULL,
    rating               VARCHAR(8) NOT NULL CHECK (rating IN ('up', 'down')),
    source               VARCHAR(16) NOT NULL DEFAULT 'chat' CHECK (source IN ('chat', 'creative', 'eval')),
    status               VARCHAR(16) NOT NULL DEFAULT 'open'
                         CHECK (status IN ('open', 'acked', 'converted', 'ignored')),
    reason               TEXT,
    tags                 TEXT[] NOT NULL DEFAULT '{}',
    refs                 JSONB NOT NULL DEFAULT '{}'::jsonb,
    converted_dataset_id UUID,
    converted_case_id    UUID,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.user_feedback IS '用户反馈与 BadCase 库（D1 数据中台）';
COMMENT ON COLUMN platform.user_feedback.refs IS '来源快照：{source, conversationId, runId, question, answer}（创建时冻结，后续编辑不影响回流用例）';
CREATE INDEX idx_user_feedback_tenant ON platform.user_feedback (tenant_id, status, created_at DESC);
CREATE INDEX idx_user_feedback_rating ON platform.user_feedback (tenant_id, rating, created_at DESC);
