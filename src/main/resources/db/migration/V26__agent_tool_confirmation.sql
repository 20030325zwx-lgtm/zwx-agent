-- 工具副作用人工确认：记录每次 run 中需要用户批准的工具请求与决定，配合 agent_run.pending_confirmation 驱动前端确认弹窗
CREATE TABLE IF NOT EXISTS agent_tool_confirmation (
    id                BIGSERIAL PRIMARY KEY,
    run_id            VARCHAR(64)  NOT NULL,
    tool_name         VARCHAR(255) NOT NULL,
    arguments_summary TEXT,
    decision          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    decided_at        TIMESTAMP,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_tool_confirmation UNIQUE (run_id, tool_name)
);

COMMENT ON TABLE agent_tool_confirmation IS '有副作用工具的人工确认台账：PENDING/APPROVED/DENIED，批准后同一 run 内放行';
COMMENT ON COLUMN agent_tool_confirmation.arguments_summary IS '参数摘要（键名与字符数），不含完整参数值，避免敏感信息入库';

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS pending_confirmation JSONB;

COMMENT ON COLUMN agent_run.pending_confirmation IS 'run 暂停等待确认时的待确认工具列表（JSON），恢复执行时清空';

CREATE INDEX IF NOT EXISTS idx_agent_tool_confirmation_run ON agent_tool_confirmation (run_id);
