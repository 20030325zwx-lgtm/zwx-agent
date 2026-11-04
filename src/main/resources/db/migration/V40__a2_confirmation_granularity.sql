-- A2（工作 Agent 整改批次 A2）：工具确认粒度扩展（方案 §7.5，expand-only）
-- 说明：V33 的 (run_id, worker_key, tool_name, arguments_hash) 唯一约束本批次不删除——
-- 新引擎（engine_version=2）的账本写入改用 invocation_key 部分唯一索引并容忍 V33 冲突回读；
-- 旧约束保留至旧引擎认领完全退役后的清理批次，避免中断存量 run 的恢复幂等。

ALTER TABLE agent_tool_confirmation
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS operation_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS arguments_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS workspace_ref VARCHAR(128),
    ADD COLUMN IF NOT EXISTS scope VARCHAR(16) NOT NULL DEFAULT 'ONCE',
    ADD COLUMN IF NOT EXISTS policy_revision BIGINT,
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS decided_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS request_version INTEGER NOT NULL DEFAULT 1;

COMMENT ON COLUMN agent_tool_confirmation.scope IS '授权范围：ONCE 仅此次 / SESSION 会话内 / PROJECT 项目规则（显式选择才写入）';
COMMENT ON COLUMN agent_tool_confirmation.operation_key IS '操作意图身份（审批先于执行生成）；UNIQUE(run_id, operation_key)';
COMMENT ON COLUMN agent_tool_confirmation.consumed_at IS '批准被实际消费的时间';
COMMENT ON COLUMN agent_tool_confirmation.decided_by IS '决定人（服务端身份填充）';

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_tool_confirmation_operation
    ON agent_tool_confirmation (run_id, operation_key)
    WHERE operation_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_tool_confirmation_pending
    ON agent_tool_confirmation (run_id, created_at)
    WHERE decision = 'PENDING';
