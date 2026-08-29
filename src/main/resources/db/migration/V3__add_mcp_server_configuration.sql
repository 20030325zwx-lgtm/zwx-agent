CREATE TABLE IF NOT EXISTS mcp_server_configuration (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    name VARCHAR(80) NOT NULL,
    endpoint VARCHAR(2048) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, name)
);

COMMENT ON TABLE mcp_server_configuration IS 'Tenant-scoped remote MCP SSE server configuration';
COMMENT ON COLUMN mcp_server_configuration.endpoint IS 'MCP SSE server base URL; stdio commands are intentionally unsupported';
CREATE INDEX IF NOT EXISTS idx_mcp_server_configuration_tenant ON mcp_server_configuration (tenant_id, enabled);
