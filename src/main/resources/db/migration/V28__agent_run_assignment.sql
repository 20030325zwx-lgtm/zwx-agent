-- 派工（Assignment）：run 是「一件被授权的任务」，assignment 是「某个执行器执行的一次绑定」。
-- 崩溃恢复 / 换执行器 / 重试都新建一行 assignment，run 本身状态不跟着执行器走。
CREATE TABLE IF NOT EXISTS agent_run_assignment (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    executor_type VARCHAR(24) NOT NULL DEFAULT 'local',
    session_ref VARCHAR(128),
    work_dir TEXT,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'ENDED')),
    result_summary TEXT,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    CONSTRAINT uq_run_assignment_attempt UNIQUE (run_id, attempt)
);

COMMENT ON TABLE agent_run_assignment IS '派工：run 的一次执行绑定（可重派/可换执行器）';
COMMENT ON COLUMN agent_run_assignment.executor_type IS '执行器类型：local（本服务）/ desktop（桌面端）/ remote（预留）';
COMMENT ON COLUMN agent_run_assignment.session_ref IS '执行器侧会话标识（如图谱实例、桌面端会话 id），仅用于排查';
COMMENT ON COLUMN agent_run_assignment.work_dir IS '该次执行的目录（工作区或项目 checkout）';

CREATE INDEX IF NOT EXISTS idx_run_assignment_run ON agent_run_assignment (run_id, attempt DESC);
