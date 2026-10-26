-- 超级智能体 token 计量：把每次 run 的模型调用开销变成可查询字段，供成本归因与优化前后对比
ALTER TABLE agent_run
    ADD COLUMN IF NOT EXISTS prompt_tokens     INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS completion_tokens INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS llm_calls         INTEGER DEFAULT 0,
    ADD COLUMN IF NOT EXISTS token_usage       JSONB;

COMMENT ON COLUMN agent_run.llm_calls IS '本次 run 的模型调用次数（worker 每步各一次，非节点数）';
COMMENT ON COLUMN agent_run.token_usage IS '按阶段的 token 与提示词构成分解（JSON）';

CREATE INDEX IF NOT EXISTS idx_agent_run_created_prompt_tokens
    ON agent_run (created_at, prompt_tokens);
