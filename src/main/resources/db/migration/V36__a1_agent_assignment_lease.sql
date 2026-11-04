-- A1：agent_run_assignment 租约与 fencing 扩展（方案 §7.2，expand-only）
-- 沿用 status=PENDING/RUNNING/ENDED，结果由 end_reason 表达，不另造第二个 run 状态。

ALTER TABLE agent_run_assignment
    ADD COLUMN IF NOT EXISTS owner_instance VARCHAR(36),
    ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS end_reason VARCHAR(48),
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64);

COMMENT ON COLUMN agent_run_assignment.owner_instance IS '认领本尝试的执行器实例';
COMMENT ON COLUMN agent_run_assignment.fencing_token IS '派发令牌：认领时从 run 行取值，写回须携带以防迟到更新';
COMMENT ON COLUMN agent_run_assignment.lease_until IS '本尝试的租约到期时间，心跳续约';
COMMENT ON COLUMN agent_run_assignment.heartbeat_at IS '最近一次心跳时间';
COMMENT ON COLUMN agent_run_assignment.end_reason IS '结束原因：COMPLETED/FAILED/LEASE_LOST/CANCELLED/SUPERSEDED/CRASHED 等';
COMMENT ON COLUMN agent_run_assignment.error_code IS '稳定错误码，不暴露内部堆栈';

CREATE INDEX IF NOT EXISTS idx_run_assignment_lease
    ON agent_run_assignment (lease_until)
    WHERE status = 'RUNNING';
