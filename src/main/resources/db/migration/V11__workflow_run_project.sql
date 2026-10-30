-- 工作流运行绑定创作项目（2026-10-30 决策：工作流作为 AI 创作的一部分承接原 C17/C18）。
-- 运行可携带目标创作项目：媒体节点的产物直接落入该项目素材库，项目详情页可按项目筛选运行。
-- NULL 表示未绑定（历史运行与独立运行不受影响）。
ALTER TABLE workflow_run ADD COLUMN IF NOT EXISTS creative_project_id UUID REFERENCES creative_project(id);

CREATE INDEX IF NOT EXISTS idx_workflow_run_project
    ON workflow_run (tenant_id, user_id, creative_project_id);
