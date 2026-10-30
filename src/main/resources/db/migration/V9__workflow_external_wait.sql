-- C13：工作流外部等待（媒体任务）与冻结图。
-- 运行状态新增 WAITING_EXTERNAL；图快照在启动时冻结，恢复不受后续版本修改影响。

ALTER TABLE workflow_run DROP CONSTRAINT IF EXISTS ck_workflow_run_status;
ALTER TABLE workflow_run ADD CONSTRAINT ck_workflow_run_status
    CHECK (status IN ('RUNNING', 'WAITING_EXTERNAL', 'SUCCEEDED', 'FAILED'));

ALTER TABLE workflow_run ADD COLUMN IF NOT EXISTS graph_snapshot JSONB;
COMMENT ON COLUMN workflow_run.graph_snapshot IS '启动时冻结的图结构；恢复等待中的媒体节点必须使用该快照';

CREATE TABLE workflow_node_execution (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    node_key VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    media_job_id UUID,
    output_json JSONB,
    error TEXT,
    attempt INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_workflow_node_execution UNIQUE (run_id, node_key),
    CONSTRAINT ck_workflow_node_execution_status
        CHECK (status IN ('WAITING_EXTERNAL', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_workflow_node_execution_waiting
    ON workflow_node_execution (status, updated_at)
    WHERE status = 'WAITING_EXTERNAL';

COMMENT ON TABLE workflow_node_execution IS '工作流节点的外部等待记录：媒体任务与节点一一对应，恢复时按 media_job 结果推进';
