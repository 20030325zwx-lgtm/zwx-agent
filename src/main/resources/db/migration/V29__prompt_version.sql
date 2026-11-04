-- 提示词版本化：不可变版本 + 活跃指针；run 记录自己使用的版本快照。
CREATE TABLE IF NOT EXISTS prompt_version (
    id BIGSERIAL PRIMARY KEY,
    prompt_key VARCHAR(48) NOT NULL,
    version INTEGER NOT NULL,
    content TEXT NOT NULL,
    base_version INTEGER NOT NULL DEFAULT 0,
    origin VARCHAR(24) NOT NULL DEFAULT 'panel',
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_prompt_version UNIQUE (prompt_key, version)
);

COMMENT ON TABLE prompt_version IS '提示词不可变版本（planner/verifier/aggregator/worker）';
COMMENT ON COLUMN prompt_version.base_version IS '保存时基于的版本号；0 表示基于内置默认内容';

CREATE TABLE IF NOT EXISTS prompt_active (
    prompt_key VARCHAR(48) PRIMARY KEY,
    version INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE prompt_active IS '各提示词当前生效的版本';
