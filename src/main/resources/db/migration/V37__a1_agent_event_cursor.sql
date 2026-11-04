-- A1：agent_execution_event 统一持久化事件扩展（方案 §7.3，expand-only）
-- sequence 升级为 BIGINT（与 run.event_seq 分配器一致）；新增结构化归属与幂等键。
-- 该表同时服务非 Manus agent，不对 run_id 强加外键；引用合法性由受控写入口保证。

ALTER TABLE agent_execution_event
    ALTER COLUMN sequence TYPE BIGINT,
    ADD COLUMN IF NOT EXISTS user_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS schema_version SMALLINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS step_id UUID,
    ADD COLUMN IF NOT EXISTS tool_call_id BIGINT,
    ADD COLUMN IF NOT EXISTS assignment_id VARCHAR(36),
    ADD COLUMN IF NOT EXISTS dedupe_key VARCHAR(160);

COMMENT ON COLUMN agent_execution_event.user_id IS '归属用户（旧事件为空，不倒填虚构值）';
COMMENT ON COLUMN agent_execution_event.event_type IS '事件类型：run/step/tool/input/command/artifact/verification 的创建、更新与终态';
COMMENT ON COLUMN agent_execution_event.schema_version IS '事件 payload 结构版本';
COMMENT ON COLUMN agent_execution_event.step_id IS '关联子任务（同 run 内）';
COMMENT ON COLUMN agent_execution_event.tool_call_id IS '关联工具调用账本行';
COMMENT ON COLUMN agent_execution_event.assignment_id IS '产生事件的执行尝试';
COMMENT ON COLUMN agent_execution_event.dedupe_key IS '业务幂等键：同 run 内唯一，重复投递只落一条';

-- 既有 UNIQUE(run_id, sequence) 继续作为游标序号的硬约束
CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_event_dedupe
    ON agent_execution_event (run_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_event_step
    ON agent_execution_event (run_id, step_id, sequence);

CREATE INDEX IF NOT EXISTS idx_agent_event_type
    ON agent_execution_event (run_id, event_type, sequence);
