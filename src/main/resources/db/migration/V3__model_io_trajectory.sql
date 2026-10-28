-- 模型调用轨迹：每次 LLM 调用的阶段、耗时、token 与请求/响应摘要。
-- 默认只存结构摘要（角色、字符数、预览），app.agent.observability.model-io-enabled+full 时存完整载荷（单条截断）。
-- 设计文档：docs/design/2026-10-28-permission-modes-and-eval-trajectory.md

CREATE TABLE agent_llm_call (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES agent_run(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64),
    sequence INTEGER NOT NULL,
    phase VARCHAR(64) NOT NULL,
    model VARCHAR(128),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    latency_ms BIGINT,
    request_digest JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_digest JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_llm_call_run
    ON agent_llm_call (run_id, id);

CREATE INDEX idx_agent_llm_call_tenant_created
    ON agent_llm_call (tenant_id, created_at DESC);

COMMENT ON TABLE agent_llm_call IS '模型调用轨迹：评测回放与成本归因的调用级证据';
COMMENT ON COLUMN agent_llm_call.phase IS '调用阶段：planner / worker·role#n / verifier / aggregator';
COMMENT ON COLUMN agent_llm_call.request_digest IS '请求摘要：消息角色与字符数、工具 schema 规模；开启 full 时含完整消息与响应';
COMMENT ON COLUMN agent_llm_call.response_digest IS '响应摘要：正文预览、工具调用、finishReason；开启 full 时含完整响应';
