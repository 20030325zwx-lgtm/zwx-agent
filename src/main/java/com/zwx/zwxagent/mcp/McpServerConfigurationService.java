package com.zwx.zwxagent.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class McpServerConfigurationService {
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private final JdbcTemplate jdbcTemplate;

    public McpServerConfigurationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<McpServerConfiguration> list(String tenantId) {
        return jdbcTemplate.query("""
                        SELECT id, name, endpoint, enabled, created_at, updated_at
                        FROM mcp_server_configuration WHERE tenant_id = ? ORDER BY name
                        """, (rs, rowNum) -> new McpServerConfiguration(rs.getLong("id"), rs.getString("name"),
                        rs.getString("endpoint"), rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()), tenantId);
    }

    @Transactional
    public McpServerConfiguration create(String tenantId, McpServerConfigurationRequest request) {
        validate(request);
        jdbcTemplate.update("INSERT INTO mcp_server_configuration (tenant_id, name, endpoint, enabled) VALUES (?, ?, ?, ?)",
                tenantId, request.name().trim(), normalizeEndpoint(request.endpoint()), request.enabled());
        return findByName(tenantId, request.name().trim());
    }

    @Transactional
    public McpServerConfiguration update(String tenantId, long id, McpServerConfigurationRequest request) {
        validate(request);
        if (jdbcTemplate.update("UPDATE mcp_server_configuration SET name = ?, endpoint = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ?",
                request.name().trim(), normalizeEndpoint(request.endpoint()), request.enabled(), id, tenantId) == 0) {
            throw new IllegalArgumentException("MCP 服务不存在或不属于当前租户");
        }
        return find(tenantId, id);
    }

    @Transactional
    public void delete(String tenantId, long id) {
        jdbcTemplate.update("DELETE FROM mcp_server_configuration WHERE id = ? AND tenant_id = ?", id, tenantId);
    }

    public McpConnectionTestResult test(String tenantId, long id) {
        McpServerConfiguration config = find(tenantId, id);
        McpSyncClient client = null;
        try {
            client = connect(config.endpoint());
            List<String> tools = client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
            return new McpConnectionTestResult(true, "已连接并发现 " + tools.size() + " 个工具", tools);
        } catch (Exception exception) {
            return new McpConnectionTestResult(false, "连接失败：" + safeMessage(exception), List.of());
        } finally {
            if (client != null) client.close();
        }
    }

    public McpTools toolsFor(String tenantId) {
        List<McpSyncClient> clients = new ArrayList<>();
        try {
            for (McpServerConfiguration config : list(tenantId)) {
                if (!config.enabled()) continue;
                clients.add(connect(config.endpoint()));
            }
            return clients.isEmpty() ? McpTools.EMPTY : new McpTools(new SyncMcpToolCallbackProvider(clients).getToolCallbacks(), clients);
        } catch (Exception exception) {
            clients.forEach(McpSyncClient::close);
            return McpTools.EMPTY;
        }
    }

    private McpSyncClient connect(String endpoint) {
        McpSyncClient client = McpClient.sync(HttpClientSseClientTransport.builder(endpoint).build())
                .requestTimeout(REQUEST_TIMEOUT).initializationTimeout(INITIALIZATION_TIMEOUT)
                .clientInfo(new McpSchema.Implementation("zwx-agent", "0.0.1"))
                .build();
        client.initialize();
        return client;
    }

    private McpServerConfiguration find(String tenantId, long id) {
        List<McpServerConfiguration> values = jdbcTemplate.query("""
                        SELECT id, name, endpoint, enabled, created_at, updated_at FROM mcp_server_configuration
                        WHERE id = ? AND tenant_id = ?
                        """, (rs, rowNum) -> new McpServerConfiguration(rs.getLong("id"), rs.getString("name"), rs.getString("endpoint"),
                        rs.getBoolean("enabled"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id, tenantId);
        if (values.isEmpty()) throw new IllegalArgumentException("MCP 服务不存在或不属于当前租户");
        return values.getFirst();
    }

    private McpServerConfiguration findByName(String tenantId, String name) {
        return list(tenantId).stream().filter(item -> item.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP 服务保存后无法读取"));
    }

    private void validate(McpServerConfigurationRequest request) {
        if (request == null || request.name() == null || request.name().isBlank() || request.name().trim().length() > 80) {
            throw new IllegalArgumentException("MCP 服务名称必须为 1-80 个字符");
        }
        normalizeEndpoint(request.endpoint());
    }

    private String normalizeEndpoint(String endpoint) {
        try {
            URI value = URI.create(endpoint == null ? "" : endpoint.trim());
            if (!("http".equalsIgnoreCase(value.getScheme()) || "https".equalsIgnoreCase(value.getScheme()))
                    || value.getHost() == null || value.getUserInfo() != null || value.getFragment() != null) {
                throw new IllegalArgumentException("MCP 地址必须是无凭据的 HTTP(S) URL");
            }
            return value.toString().replaceAll("/$", "");
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("MCP 地址必须是有效的 HTTP(S) URL");
        }
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replaceAll("[\\r\\n]", " ");
    }
}
