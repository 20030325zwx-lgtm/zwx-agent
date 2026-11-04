-- A1：可靠执行底座 4 张新表（方案 §8.1）
-- 公共约定：tenant_id/user_id 服务端填充；主键 UUID；状态 VARCHAR+CHECK；
-- 引用 agent_run 使用 (id, tenant_id, user_id) 复合外键防跨租户关联（候选键见 V35）。

-- ---------------------------------------------------------------------------
-- agent_run_step：子任务与依赖
-- ---------------------------------------------------------------------------
CREATE TABLE agent_run_step (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    plan_revision INTEGER NOT NULL,
    step_key VARCHAR(128) NOT NULL,
    parent_step_id UUID,
    title VARCHAR(255) NOT NULL,
    role_key VARCHAR(64) NOT NULL,
    definition_version_id UUID,
    input_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    depends_on JSONB NOT NULL DEFAULT '[]'::jsonb,
    acceptance JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(24) NOT NULL CHECK (status IN
        ('PENDING', 'READY', 'RUNNING', 'WAITING_HUMAN', 'SUCCEEDED', 'FAILED', 'SKIPPED', 'BLOCKED', 'CANCELLED')),
    attempt INTEGER NOT NULL DEFAULT 0,
    state_version BIGINT NOT NULL DEFAULT 0,
    assignment_id VARCHAR(36),
    result_summary TEXT,
    checkpoint_id UUID,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_run_step_key UNIQUE (run_id, plan_revision, step_key),
    CONSTRAINT fk_agent_run_step_run FOREIGN KEY (run_id, tenant_id, user_id)
        REFERENCES agent_run (id, tenant_id, user_id) ON DELETE CASCADE
);

COMMENT ON TABLE agent_run_step IS '子任务：计划中的一个步骤，含输入快照、依赖、验收与结果';
COMMENT ON COLUMN agent_run_step.step_key IS '计划内稳定键（同 run 同计划版本唯一）';
COMMENT ON COLUMN agent_run_step.depends_on IS '依赖的 step_key 数组，仅允许同 run 同 plan_revision；写入时校验 DAG';
COMMENT ON COLUMN agent_run_step.acceptance IS '验收条件数组（确定性条件先行）';
COMMENT ON COLUMN agent_run_step.state_version IS '状态乐观锁版本';

CREATE INDEX idx_agent_run_step_status ON agent_run_step (run_id, status);
CREATE INDEX idx_agent_run_step_updated ON agent_run_step (tenant_id, user_id, updated_at DESC);

-- ---------------------------------------------------------------------------
-- agent_run_input：问题、答案、纠偏和队列
-- ---------------------------------------------------------------------------
CREATE TABLE agent_run_input (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36),
    step_id UUID,
    parent_input_id UUID,
    input_seq BIGINT GENERATED ALWAYS AS IDENTITY,
    kind VARCHAR(24) NOT NULL CHECK (kind IN
        ('QUESTION', 'ANSWER', 'STEER', 'NEXT_TURN', 'PLAN_APPROVAL', 'CONFIRMATION_DECISION')),
    payload JSONB NOT NULL,
    client_request_key VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN
        ('PENDING', 'ACCEPTED', 'APPLIED', 'REJECTED', 'CANCELLED', 'SUPERSEDED')),
    version INTEGER NOT NULL DEFAULT 1,
    created_by VARCHAR(64),
    answer_deadline TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    applied_at TIMESTAMPTZ,
    result_run_id VARCHAR(36),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_run_input_request UNIQUE (tenant_id, user_id, client_request_key),
    CONSTRAINT fk_agent_run_input_run FOREIGN KEY (run_id, tenant_id, user_id)
        REFERENCES agent_run (id, tenant_id, user_id) ON DELETE CASCADE
);

COMMENT ON TABLE agent_run_input IS 'run 级用户输入队列：提问/答复/纠偏/下一轮/计划批准，答复与原问题关联且只消费一次';
COMMENT ON COLUMN agent_run_input.input_seq IS '会话内输入顺序（身份列）';
COMMENT ON COLUMN agent_run_input.payload IS '结构化内容：题目、选项、答复文本、纠偏目标等（含 schema_version）';
COMMENT ON COLUMN agent_run_input.result_run_id IS 'NEXT_TURN 消费后创建的新 run';

CREATE INDEX idx_agent_run_input_conversation ON agent_run_input (conversation_id, status, input_seq);
CREATE INDEX idx_agent_run_input_run ON agent_run_input (run_id, status);

-- ---------------------------------------------------------------------------
-- agent_run_checkpoint：可恢复上下文
-- ---------------------------------------------------------------------------
CREATE TABLE agent_run_checkpoint (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    step_id UUID,
    checkpoint_no BIGINT NOT NULL,
    schema_version SMALLINT NOT NULL,
    plan_revision INTEGER NOT NULL,
    state_json JSONB NOT NULL,
    summary_json JSONB NOT NULL,
    message_object_key TEXT,
    content_hash CHAR(64) NOT NULL,
    last_event_seq BIGINT NOT NULL,
    last_consumed_input_id UUID,
    reason VARCHAR(32) NOT NULL CHECK (reason IN
        ('WAITING', 'BEFORE_EFFECT', 'AFTER_STEP', 'COMPRESSION', 'PAUSE')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_run_checkpoint_no UNIQUE (run_id, checkpoint_no),
    CONSTRAINT fk_agent_run_checkpoint_run FOREIGN KEY (run_id, tenant_id, user_id)
        REFERENCES agent_run (id, tenant_id, user_id) ON DELETE CASCADE
);

COMMENT ON TABLE agent_run_checkpoint IS '新引擎恢复检查点：恢复输入的权威版本（旧 agent_run 图检查点仅为旧引擎兼容缓存）';
COMMENT ON COLUMN agent_run_checkpoint.summary_json IS '任务摘要：任务契约、完成/未完成清单、关键决策、引用索引';
COMMENT ON COLUMN agent_run_checkpoint.content_hash IS 'state_json 内容哈希，用于完整性校验';
COMMENT ON COLUMN agent_run_checkpoint.last_event_seq IS '检查点覆盖到的最后事件序号';
COMMENT ON COLUMN agent_run_checkpoint.reason IS '打点原因：WAITING/BEFORE_EFFECT/AFTER_STEP/COMPRESSION/PAUSE';

CREATE INDEX idx_agent_run_checkpoint_run ON agent_run_checkpoint (run_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- agent_run_evidence：可核对的验收证据
-- ---------------------------------------------------------------------------
CREATE TABLE agent_run_evidence (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    step_id UUID,
    tool_call_id BIGINT,
    kind VARCHAR(32) NOT NULL CHECK (kind IN
        ('FILE', 'COMMAND_EXIT', 'TEST_REPORT', 'BROWSER_ASSERTION', 'MEDIA_OUTPUT', 'TEXT')),
    producer VARCHAR(32) NOT NULL,
    artifact_ref TEXT,
    content_hash CHAR(64),
    media_type VARCHAR(128),
    size_bytes BIGINT,
    result_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    verification_status VARCHAR(24) NOT NULL DEFAULT 'NOT_RUN' CHECK (verification_status IN
        ('PASS', 'FAIL', 'NOT_RUN', 'INCONCLUSIVE')),
    verified_at TIMESTAMPTZ,
    criterion_key VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_run_evidence_run FOREIGN KEY (run_id, tenant_id, user_id)
        REFERENCES agent_run (id, tenant_id, user_id) ON DELETE CASCADE
);

COMMENT ON TABLE agent_run_evidence IS '验收证据：文件/退出码/测试报告/浏览器断言/媒体产物，验收必须引用 evidence_id';
COMMENT ON COLUMN agent_run_evidence.producer IS '产生方：AGENT（本 run 生成）/ USER_UPLOAD / SYSTEM，不得把用户上传误当 Agent 产物';
COMMENT ON COLUMN agent_run_evidence.artifact_ref IS '产物引用（对象存储键或工作区相对路径），访问走短时授权地址';

CREATE INDEX idx_agent_run_evidence_step ON agent_run_evidence (run_id, step_id, kind);
CREATE INDEX idx_agent_run_evidence_verification ON agent_run_evidence (run_id, verification_status);
