-- ---------------------------------------------------------------------------
-- V49：run_event——run 的 append-only 事件日志（真相层）
-- 融合治理：日志是唯一真相，agent_run_checkpoint（v2 快照）是"日志到 seq N 的重放缓存"；
-- 快照记录 last_event_seq，可由日志重放验证/重建。凡进模型消息历史的事件必须先落本表。
-- ---------------------------------------------------------------------------

CREATE TABLE agent_run_event (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    seq BIGINT NOT NULL,
    type VARCHAR(48) NOT NULL,
    data JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_run_event_seq UNIQUE (run_id, seq),
    CONSTRAINT fk_agent_run_event_run FOREIGN KEY (run_id, tenant_id, user_id)
        REFERENCES agent_run (id, tenant_id, user_id) ON DELETE CASCADE
);

CREATE INDEX idx_agent_run_event_run ON agent_run_event (run_id, seq);
CREATE INDEX idx_agent_run_event_type ON agent_run_event (run_id, type);

COMMENT ON TABLE agent_run_event IS 'run 事件日志（append-only）：恢复/审计/修尾的真相来源';
COMMENT ON COLUMN agent_run_event.seq IS 'run 内单调递增序号（写前锁 run 行分配），快照 last_event_seq 指向此处';
COMMENT ON COLUMN agent_run_event.type IS '事件类型：run/start|end、step/start|end、tool/call|result|repair、assistant/message|attempt 等';
