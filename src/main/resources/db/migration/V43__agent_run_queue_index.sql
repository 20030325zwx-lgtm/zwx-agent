-- 派发队列索引（2026-11-05）：让 claim 查询真正走「有序索引 + SKIP LOCKED」。
-- 原索引 idx_agent_run_queue(next_dispatch_at, id) WHERE phase='QUEUED' 无法支撑
-- ORDER BY COALESCE(next_dispatch_at, created_at)，且谓词缺 engine_version/control_state。

-- 1) 兜底补值：历史排队行若缺 next_dispatch_at，用创建时间补齐；并给列默认值，避免新写入漏填。
UPDATE agent_run SET next_dispatch_at = created_at
 WHERE phase = 'QUEUED' AND next_dispatch_at IS NULL;
ALTER TABLE agent_run ALTER COLUMN next_dispatch_at SET DEFAULT now();

-- 2) 与 claim 的 WHERE/ORDER BY 完全对齐的复合部分索引。
DROP INDEX IF EXISTS idx_agent_run_queue;
CREATE INDEX IF NOT EXISTS idx_agent_run_queue
    ON agent_run (engine_version, control_state, next_dispatch_at, id)
    WHERE phase = 'QUEUED';
