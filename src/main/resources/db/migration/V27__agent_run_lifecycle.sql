-- 运行阶段语义化（对齐 dsh-superagent 的 Ticket 阶段模型）：
-- 保留 status 兼容旧前端/恢复逻辑，新增 phase / waiting_* / background_* 作为对外的规范状态。
ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS phase VARCHAR(24) NOT NULL DEFAULT 'WORKING',
    ADD COLUMN IF NOT EXISTS waiting_reason VARCHAR(24),
    ADD COLUMN IF NOT EXISTS waiting_detail TEXT,
    ADD COLUMN IF NOT EXISTS background_jobs INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS background_deadline TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS prompt_versions JSONB;

COMMENT ON COLUMN agent_run.phase IS '规范运行阶段：QUEUED/WORKING/WAITING_HUMAN/BLOCKED/FAILED/DONE/STOPPED';
COMMENT ON COLUMN agent_run.waiting_reason IS '等待人工的原因：CONFIRM_PLAN/CONFIRM_TOOL/NEED_INPUT';
COMMENT ON COLUMN agent_run.waiting_detail IS '等待人工的具体问题或说明（结构化提问的正文）';
COMMENT ON COLUMN agent_run.background_jobs IS '本轮结束后仍在后台运行的媒体任务数（>0 时 phase 保持 WORKING）';
COMMENT ON COLUMN agent_run.background_deadline IS '后台任务保留 slot 的截止时间（默认 30 分钟），超时置为 BLOCKED';
COMMENT ON COLUMN agent_run.prompt_versions IS '本次 run 使用的提示词版本快照（key → version，0 表示内置默认）';

-- 回填历史数据：按 status + 既有字段推导 phase
UPDATE agent_run SET phase = CASE
    WHEN status = 'COMPLETED' THEN 'DONE'
    WHEN status = 'FAILED' THEN 'FAILED'
    WHEN status = 'INTERRUPTED' AND verification_status = 'plan_pending' THEN 'WAITING_HUMAN'
    WHEN status = 'INTERRUPTED' AND pending_confirmation IS NOT NULL THEN 'WAITING_HUMAN'
    WHEN status = 'INTERRUPTED' THEN 'STOPPED'
    ELSE 'WORKING'
END
WHERE phase = 'WORKING';

UPDATE agent_run SET waiting_reason = CASE
    WHEN phase <> 'WAITING_HUMAN' THEN NULL
    WHEN verification_status = 'plan_pending' THEN 'CONFIRM_PLAN'
    WHEN pending_confirmation IS NOT NULL THEN 'CONFIRM_TOOL'
    ELSE NULL
END;

CREATE INDEX IF NOT EXISTS idx_agent_run_phase ON agent_run (phase, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_run_background ON agent_run (background_jobs)
    WHERE background_jobs > 0;
