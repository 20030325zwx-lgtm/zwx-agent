-- 工作流运行：工作流垂直试点的最小运行模型。
-- 定义（WorkflowDefinition）暂以代码注册表形式存在，运行（Run）持久化：
-- 步骤状态与产物均存 JSONB，后续引入原生画布编辑器时再拆定义/版本表。
-- 设计文档：docs/design/2026-10-28-ai-workbench-references.md 第 5、7 节

CREATE TABLE workflow_run (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    workflow_key VARCHAR(64) NOT NULL,
    definition_version INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL,
    current_step VARCHAR(48),
    input JSONB NOT NULL DEFAULT '{}'::jsonb,
    steps JSONB NOT NULL DEFAULT '[]'::jsonb,
    artifacts JSONB NOT NULL DEFAULT '[]'::jsonb,
    error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_workflow_run_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_workflow_run_user ON workflow_run (tenant_id, user_id, created_at DESC);

COMMENT ON TABLE workflow_run IS '工作流运行：一次执行的步骤状态与产物；定义版本绑定 workflow_key+definition_version';
COMMENT ON COLUMN workflow_run.steps IS '步骤状态数组：[{key,name,status,message,startedAt,finishedAt}]，status 为 PENDING/RUNNING/SUCCEEDED/SKIPPED/FAILED';
COMMENT ON COLUMN workflow_run.artifacts IS '产物数组：[{type,name,objectKey}]，访问 URL 由接口按需签名';
