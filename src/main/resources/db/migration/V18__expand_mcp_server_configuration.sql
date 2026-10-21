ALTER TABLE mcp_server_configuration
    ADD COLUMN IF NOT EXISTS auth_type VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS auth_header_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS secret_ciphertext TEXT,
    ADD COLUMN IF NOT EXISTS secret_hint VARCHAR(32),
    ADD COLUMN IF NOT EXISTS allowed_tools JSONB;

ALTER TABLE mcp_server_configuration
    ADD CONSTRAINT chk_mcp_auth_type CHECK (auth_type IN ('NONE', 'BEARER', 'HEADER'));

COMMENT ON COLUMN mcp_server_configuration.allowed_tools IS 'JSON array of explicitly authorized MCP tool names; NULL preserves legacy allow-all behavior';
COMMENT ON COLUMN mcp_server_configuration.secret_ciphertext IS 'AES-GCM encrypted MCP authentication secret';
