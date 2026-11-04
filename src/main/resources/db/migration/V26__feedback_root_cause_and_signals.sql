-- ---------------------------------------------------------------------------
-- 数据中台 D2：BadCase 根因、处理结论，以及隐式信号（采纳/编辑/重生成/复制/导出）。
-- 设计依据：docs/design/2026-11-04-data-eval-platform-detail-design.md §2.1/§2.2
-- ---------------------------------------------------------------------------

ALTER TABLE platform.user_feedback
    ADD COLUMN root_cause VARCHAR(32),
    ADD COLUMN assignee   VARCHAR(64),
    ADD COLUMN resolution TEXT;
COMMENT ON COLUMN platform.user_feedback.root_cause IS '根因：knowledge_gap/retrieval_miss/prompt_weak/tool_args/intent_misroute/refusal_fail/latency/other';
COMMENT ON COLUMN platform.user_feedback.resolution IS '处理结论（回流时带入用例 notes）';

CREATE INDEX idx_user_feedback_root_cause ON platform.user_feedback (tenant_id, root_cause, created_at DESC);

-- 隐式信号：append-only，与显式反馈解耦（采纳率/编辑占比/重生成率/导出率的原始事实）
CREATE TABLE platform.user_signal (
    id           BIGSERIAL PRIMARY KEY,
    tenant_id    VARCHAR(64) NOT NULL,
    user_id      VARCHAR(64),
    event        VARCHAR(32) NOT NULL,
    subject_type VARCHAR(16) NOT NULL,
    subject_id   VARCHAR(64) NOT NULL,
    props        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
COMMENT ON TABLE platform.user_signal IS '隐式行为信号（adopt/edit/regenerate/copy/export），用于单次采纳成本与质量交叉验证';
CREATE INDEX idx_user_signal_tenant_event ON platform.user_signal (tenant_id, event, created_at DESC);
CREATE INDEX idx_user_signal_subject ON platform.user_signal (subject_type, subject_id);
