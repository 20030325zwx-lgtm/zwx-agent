CREATE TABLE IF NOT EXISTS agent_instance (
    instance_id VARCHAR(36) PRIMARY KEY,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_instance_heartbeat
    ON agent_instance (heartbeat_at DESC);

ALTER TABLE agent_conversation_lock
    ADD COLUMN IF NOT EXISTS owner_instance VARCHAR(36);

ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS owner_instance VARCHAR(36);

CREATE INDEX IF NOT EXISTS idx_agent_run_owner
    ON agent_run (owner_instance, status);

COMMENT ON TABLE agent_instance IS '服务实例心跳，用于区分存活实例与崩溃实例留下的租约';
