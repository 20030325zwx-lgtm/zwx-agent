-- 系统/用户级配置：按 scope 覆盖环境变量，密钥加密存储。
CREATE TABLE IF NOT EXISTS app_configuration (
    id BIGSERIAL PRIMARY KEY,
    scope_type VARCHAR(16) NOT NULL,
    owner_id VARCHAR(64) NOT NULL DEFAULT '',
    config_type VARCHAR(16) NOT NULL,
    config_key VARCHAR(64) NOT NULL,
    provider VARCHAR(32),
    base_url VARCHAR(512),
    model_name VARCHAR(128),
    secret_ciphertext TEXT,
    secret_hint VARCHAR(32),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    extra JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_app_configuration_scope CHECK (scope_type IN ('SYSTEM', 'USER')),
    CONSTRAINT chk_app_configuration_type CHECK (config_type IN ('MODEL', 'SERVICE'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_app_configuration_scope_key
    ON app_configuration (scope_type, owner_id, config_type, config_key);

CREATE INDEX IF NOT EXISTS idx_app_configuration_owner
    ON app_configuration (scope_type, owner_id, config_type);

COMMENT ON TABLE app_configuration IS '应用配置：用户/系统级模型与外部服务覆盖项，密钥使用 AES-GCM 加密';
COMMENT ON COLUMN app_configuration.scope_type IS 'SYSTEM=系统默认，USER=用户个人覆盖';
COMMENT ON COLUMN app_configuration.owner_id IS 'USER 作用域的用户 ID；SYSTEM 作用域为空字符串';
COMMENT ON COLUMN app_configuration.config_key IS '配置项标识，例如 chat/vision/docling';
COMMENT ON COLUMN app_configuration.secret_ciphertext IS 'AES-GCM 加密后的密钥，禁止明文落库';
COMMENT ON COLUMN app_configuration.secret_hint IS '密钥末位提示，用于前端掩码展示';
