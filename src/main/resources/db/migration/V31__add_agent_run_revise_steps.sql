-- 步骤级返工：持久化质检点名的返工步骤序号，恢复 run 后仍能只重跑相关步骤
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS revise_steps JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN agent_run.revise_steps IS '质检点名需要返工的子任务序号（0 起，对齐计划顺序；空数组表示全部重做）';
