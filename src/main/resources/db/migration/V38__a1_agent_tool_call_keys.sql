-- A1：agent_tool_call 调用身份与业务结果扩展（方案 §7.4，expand-only）
-- 注意：V33 的 (run_id, worker_key, tool_name, arguments_hash) 唯一约束【本批次不删除】，
-- 按方案要求该切换必须在新 dispatcher/wrapper 就绪且旧执行器退出后（A2）执行。
-- 本批次仅：加列 + 回填 invocation_key（legacy 前缀）+ 建部分唯一索引。

ALTER TABLE agent_tool_call
    ADD COLUMN IF NOT EXISTS step_id UUID,
    ADD COLUMN IF NOT EXISTS invocation_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS operation_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS replay_of_call_id BIGINT,
    ADD COLUMN IF NOT EXISTS business_outcome VARCHAR(24),
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS duration_ms BIGINT,
    ADD COLUMN IF NOT EXISTS result_object_key TEXT,
    ADD COLUMN IF NOT EXISTS display_arguments JSONB,
    ADD COLUMN IF NOT EXISTS schema_version SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN agent_tool_call.step_id IS '归属子任务（agent_run_step.id，可空）';
COMMENT ON COLUMN agent_tool_call.invocation_key IS '一次逻辑调用的身份：与 step/checkpoint 绑定，重放不变';
COMMENT ON COLUMN agent_tool_call.operation_key IS '有副作用业务动作的稳定去重身份，由可信服务分配，模型不得生成';
COMMENT ON COLUMN agent_tool_call.replay_of_call_id IS '复用尝试指向的原调用行（复用行 operation_key 为空）';
COMMENT ON COLUMN agent_tool_call.business_outcome IS '业务结果：SUCCEEDED/FAILED/PARTIAL/UNKNOWN，与传输状态 status 分离';
COMMENT ON COLUMN agent_tool_call.error_code IS '稳定错误码';
COMMENT ON COLUMN agent_tool_call.duration_ms IS '调用耗时（毫秒）';
COMMENT ON COLUMN agent_tool_call.result_object_key IS '大结果的对象存储键（>阈值时明细外置）';
COMMENT ON COLUMN agent_tool_call.display_arguments IS '脱敏后的展示参数（与执行用参数分离）';

-- 旧账本行回填 legacy 身份，保证新唯一索引可建立且旧行语义不变
UPDATE agent_tool_call
SET invocation_key = 'legacy:' || id
WHERE invocation_key IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_tool_call_invocation
    ON agent_tool_call (run_id, invocation_key)
    WHERE invocation_key IS NOT NULL;

-- 有副作用操作的幂等索引（A2 写入口启用）
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_tool_call_operation
    ON agent_tool_call (run_id, operation_key)
    WHERE operation_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_tool_call_step
    ON agent_tool_call (run_id, step_id, id);
