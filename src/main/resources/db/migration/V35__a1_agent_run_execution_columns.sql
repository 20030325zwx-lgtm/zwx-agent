-- A1（工作 Agent 整改批次 A1）：agent_run 执行底座列扩展（expand-only，不切换任何写入方）
-- 方案 docs/design/2026-11-05-work-agent-remediation-plan.md §7.1。
-- engine_version=1 继续走旧恢复逻辑；新引擎（A2）写 2。所有列带默认值，旧读者不受影响。

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS engine_version SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS execution_mode VARCHAR(16) NOT NULL DEFAULT 'INTERACTIVE',
    ADD COLUMN IF NOT EXISTS trigger_source VARCHAR(16) NOT NULL DEFAULT 'CHAT',
    ADD COLUMN IF NOT EXISTS start_request_key VARCHAR(128),
    ADD COLUMN IF NOT EXISTS state_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS plan_revision INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS event_seq BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS control_state VARCHAR(24) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS stop_reason VARCHAR(48),
    ADD COLUMN IF NOT EXISTS recovery_policy VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN IF NOT EXISTS next_dispatch_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS fencing_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS budget_config JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS deadline_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS context_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_consumed_input_id UUID;

COMMENT ON COLUMN agent_run.engine_version IS '执行引擎版本：1 旧恢复逻辑，2 新持久化底座';
COMMENT ON COLUMN agent_run.execution_mode IS '执行模式：INTERACTIVE 交互中 / BACKGROUND 后台运行';
COMMENT ON COLUMN agent_run.trigger_source IS '触发来源：CHAT/GOAL/SCHEDULE';
COMMENT ON COLUMN agent_run.start_request_key IS '外部启动幂等键（仅非空唯一）';
COMMENT ON COLUMN agent_run.state_version IS '状态乐观锁版本，与返工计数 revision 分离';
COMMENT ON COLUMN agent_run.plan_revision IS '计划版本（STEER 失效旧计划时递增）';
COMMENT ON COLUMN agent_run.event_seq IS '事件序号分配器：写事件时原子递增，与事件表 sequence 一致';
COMMENT ON COLUMN agent_run.control_state IS '控制请求：NONE/PAUSE_REQUESTED/CANCEL_REQUESTED';
COMMENT ON COLUMN agent_run.stop_reason IS '停止原因：USER_PAUSED/USER_CANCELLED/UNKNOWN_EFFECT/BUDGET_EXHAUSTED 等';
COMMENT ON COLUMN agent_run.recovery_policy IS '恢复策略：MANUAL/SAFE_AUTO（历史默认不自动重放）';
COMMENT ON COLUMN agent_run.next_dispatch_at IS '排队/延迟重试的最早可派发时间';
COMMENT ON COLUMN agent_run.lease_until IS '执行租约到期时间，owner_instance 复用既有字段';
COMMENT ON COLUMN agent_run.fencing_token IS '派发令牌：每次认领递增，拒绝旧执行器迟到写入';
COMMENT ON COLUMN agent_run.last_heartbeat_at IS '执行器最近心跳时间';
COMMENT ON COLUMN agent_run.budget_config IS '本次尝试预算（时长/调用数/token，含 schema_version）';
COMMENT ON COLUMN agent_run.deadline_at IS '本次 run 截止时间（不覆盖后台媒体 deadline）';
COMMENT ON COLUMN agent_run.context_version IS '当前任务摘要/上下文版本';
COMMENT ON COLUMN agent_run.last_consumed_input_id IS '最近应用的用户输入（agent_run_input.id）';

-- 事件序号回填：与事件表当前最大 sequence 对齐，避免新事件序号冲突
UPDATE agent_run r
SET event_seq = COALESCE((SELECT MAX(e.sequence) FROM agent_execution_event e WHERE e.run_id = r.id), 0)
WHERE r.event_seq = 0;

-- token 计量列扩容：长目标模式避免溢出
ALTER TABLE agent_run
    ALTER COLUMN prompt_tokens TYPE BIGINT,
    ALTER COLUMN completion_tokens TYPE BIGINT,
    ALTER COLUMN llm_calls TYPE BIGINT;

-- 复合所有权候选键：新业务表通过 (run_id, tenant_id, user_id) 复合外键防跨租户关联
ALTER TABLE agent_run
    ADD CONSTRAINT uq_agent_run_owner_composite UNIQUE (id, tenant_id, user_id);

-- 外部启动幂等（仅非空唯一）
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_run_start_request
    ON agent_run (tenant_id, user_id, start_request_key)
    WHERE start_request_key IS NOT NULL;

-- 队列扫描与租约监控的部分索引
CREATE INDEX IF NOT EXISTS idx_agent_run_queue
    ON agent_run (next_dispatch_at, id)
    WHERE phase = 'QUEUED';

CREATE INDEX IF NOT EXISTS idx_agent_run_lease
    ON agent_run (lease_until)
    WHERE phase = 'WORKING';
