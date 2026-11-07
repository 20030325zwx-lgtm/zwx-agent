-- 定时任务（对标 ZCode automations）：到点以自然语言指令在专属会话里拉起智能体运行。
-- 任务定义与触发记录分离；运行本身复用 agent_run（状态/确认/配额/轨迹全部现成）。
CREATE TABLE agent_scheduled_task (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    instruction TEXT NOT NULL,
    conversation_id VARCHAR(64),                 -- 会话连续：首次触发创建后回填；NULL=每次全新
    session_continuity BOOLEAN NOT NULL DEFAULT TRUE,
    permission_mode VARCHAR(16) NOT NULL DEFAULT 'GUARDED',
    model_row_id BIGINT,
    schedule_kind VARCHAR(8) NOT NULL CHECK (schedule_kind IN ('DAILY', 'WEEKLY', 'ONCE')),
    run_time TIME NOT NULL,                      -- 本地（Asia/Shanghai）时刻
    weekly_dow SMALLINT CHECK (weekly_dow BETWEEN 1 AND 7),  -- ISO-8601：1=周一 … 7=周日
    once_at TIMESTAMPTZ,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_run_at TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_agent_sched_once CHECK (schedule_kind <> 'ONCE' OR once_at IS NOT NULL),
    CONSTRAINT ck_agent_sched_weekly CHECK (schedule_kind <> 'WEEKLY' OR weekly_dow IS NOT NULL)
);
CREATE INDEX idx_agent_sched_due ON agent_scheduled_task (next_run_at) WHERE enabled;
CREATE INDEX idx_agent_sched_user ON agent_scheduled_task (tenant_id, user_id, created_at DESC);

CREATE TABLE agent_scheduled_fire (
    id VARCHAR(64) PRIMARY KEY,
    task_id VARCHAR(64) NOT NULL REFERENCES agent_scheduled_task(id) ON DELETE CASCADE,
    run_id VARCHAR(64),
    trigger_kind VARCHAR(8) NOT NULL DEFAULT 'SCHEDULE' CHECK (trigger_kind IN ('SCHEDULE', 'MANUAL')),
    error TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_agent_sched_fire_task ON agent_scheduled_fire (task_id, started_at DESC);
