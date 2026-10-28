-- 模型供应商目录：Provider（连接与密钥）/ Model（供应商下模型）/ Binding（用途绑定）。
-- 取代 app_configuration 的 chat/vision 双槽位；旧行由本迁移转换为目录数据，保留原表兼容回退。
-- 设计文档：docs/design/2026-10-28-desktop-phase-a-runtime-model-contract.md 第 7 节

CREATE TABLE model_provider (
    id BIGSERIAL PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL DEFAULT '',
    name VARCHAR(64) NOT NULL,
    protocol VARCHAR(32) NOT NULL,
    base_url VARCHAR(512),
    secret_ciphertext TEXT,
    secret_hint VARCHAR(32),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    last_test_ok BOOLEAN,
    last_test_message VARCHAR(512),
    last_test_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_model_provider_protocol CHECK (protocol IN ('dashscope', 'openai-compatible'))
);

COMMENT ON TABLE model_provider IS '模型供应商：连接与密钥；owner_id='''' 为系统级（管理员管理），其余为个人供应商';
COMMENT ON COLUMN model_provider.secret_ciphertext IS 'API Key 密文（AES-256-GCM，与 app_configuration 同一加密器）';

-- 同一用户下完全相同的连接（含密钥）视为同一供应商，避免迁移与保存重复
CREATE UNIQUE INDEX uq_model_provider_identity
    ON model_provider (owner_id, protocol, COALESCE(base_url, ''), COALESCE(secret_ciphertext, ''));

CREATE INDEX idx_model_provider_owner ON model_provider (owner_id, enabled);

CREATE TABLE model_provider_model (
    id BIGSERIAL PRIMARY KEY,
    provider_id BIGINT NOT NULL REFERENCES model_provider(id) ON DELETE CASCADE,
    model_id VARCHAR(128) NOT NULL,
    display_name VARCHAR(128),
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_model_provider_model UNIQUE (provider_id, model_id)
);

COMMENT ON TABLE model_provider_model IS '供应商下的模型条目；capabilities 如 ["tools","vision"]，未验证的能力不声明';

CREATE TABLE model_binding (
    id BIGSERIAL PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    owner_id VARCHAR(64) NOT NULL DEFAULT '',
    purpose VARCHAR(32) NOT NULL,
    provider_id BIGINT NOT NULL REFERENCES model_provider(id) ON DELETE CASCADE,
    model_row_id BIGINT NOT NULL REFERENCES model_provider_model(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_model_binding UNIQUE (scope_type, owner_id, purpose)
);

COMMENT ON TABLE model_binding IS '用途绑定：purpose(chat/vision) 在用户/系统作用域下指向唯一模型；解析顺序 用户 > 系统 > 旧配置 > 环境变量';

-- 目录修订号：任何目录/绑定变更 +1；运行开始时冻结到 agent_run.model_revision
CREATE TABLE model_catalog_state (
    id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO model_catalog_state (id) VALUES (1) ON CONFLICT DO NOTHING;

-- 运行冻结的模型身份：评测回放与恢复时按此重建客户端，不随全局修改漂移
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS model_revision BIGINT;
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS model_provider VARCHAR(64);
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS model_name VARCHAR(128);

-- 迁移旧 chat/vision 配置：供应商按（归属+协议+地址+密钥）去重，密文原样转移
INSERT INTO model_provider (owner_id, name, protocol, base_url, secret_ciphertext, secret_hint, enabled)
SELECT DISTINCT
    CASE WHEN c.scope_type = 'SYSTEM' THEN '' ELSE c.owner_id END,
    CASE
        WHEN c.scope_type = 'SYSTEM' THEN '系统 ' || CASE WHEN c.provider = 'dashscope' THEN 'DashScope' ELSE 'OpenAI 兼容' END
        ELSE CASE WHEN c.provider = 'dashscope' THEN 'DashScope' ELSE 'OpenAI 兼容' END || '（迁移）'
    END,
    c.provider,
    c.base_url,
    c.secret_ciphertext,
    c.secret_hint,
    c.enabled
FROM app_configuration c
WHERE c.config_type = 'MODEL' AND c.config_key IN ('chat', 'vision')
  AND c.provider IN ('dashscope', 'openai-compatible')
ON CONFLICT DO NOTHING;

INSERT INTO model_provider_model (provider_id, model_id, display_name, enabled)
SELECT p.id, c.model_name, c.model_name, TRUE
FROM app_configuration c
JOIN model_provider p
  ON p.owner_id = (CASE WHEN c.scope_type = 'SYSTEM' THEN '' ELSE c.owner_id END)
 AND p.protocol = c.provider
 AND COALESCE(p.base_url, '') = COALESCE(c.base_url, '')
 AND COALESCE(p.secret_ciphertext, '') = COALESCE(c.secret_ciphertext, '')
WHERE c.config_type = 'MODEL' AND c.config_key IN ('chat', 'vision')
  AND c.provider IN ('dashscope', 'openai-compatible')
  AND c.model_name IS NOT NULL AND c.model_name <> ''
ON CONFLICT DO NOTHING;

INSERT INTO model_binding (scope_type, owner_id, purpose, provider_id, model_row_id)
SELECT c.scope_type,
       CASE WHEN c.scope_type = 'SYSTEM' THEN '' ELSE c.owner_id END,
       c.config_key,
       p.id,
       m.id
FROM app_configuration c
JOIN model_provider p
  ON p.owner_id = (CASE WHEN c.scope_type = 'SYSTEM' THEN '' ELSE c.owner_id END)
 AND p.protocol = c.provider
 AND COALESCE(p.base_url, '') = COALESCE(c.base_url, '')
 AND COALESCE(p.secret_ciphertext, '') = COALESCE(c.secret_ciphertext, '')
JOIN model_provider_model m
  ON m.provider_id = p.id AND m.model_id = c.model_name
WHERE c.config_type = 'MODEL' AND c.config_key IN ('chat', 'vision')
  AND c.provider IN ('dashscope', 'openai-compatible')
ON CONFLICT DO NOTHING;
