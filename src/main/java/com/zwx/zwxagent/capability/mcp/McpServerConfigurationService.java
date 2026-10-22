package com.zwx.zwxagent.capability.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.platform.settings.ConfigSecretCipher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class McpServerConfigurationService {
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(10);
    private final Duration requestTimeout;
    private final JdbcTemplate jdbcTemplate;
    private final com.zwx.zwxagent.capability.tools.UrlAccessPolicy urlAccessPolicy;
    private final ConfigSecretCipher secretCipher;
    private final ObjectMapper objectMapper;
    private final boolean allowPrivateEndpoints;

    public McpServerConfigurationService(JdbcTemplate jdbcTemplate, com.zwx.zwxagent.capability.tools.UrlAccessPolicy urlAccessPolicy,
                                         ConfigSecretCipher secretCipher, ObjectMapper objectMapper,
                                         @org.springframework.beans.factory.annotation.Value("${app.mcp.request-timeout-seconds:600}") long requestTimeoutSeconds,
                                         @org.springframework.beans.factory.annotation.Value("${app.mcp.allow-private-endpoints:false}") boolean allowPrivateEndpoints) {
        this.jdbcTemplate = jdbcTemplate;
        this.urlAccessPolicy = urlAccessPolicy;
        this.secretCipher = secretCipher;
        this.objectMapper = objectMapper;
        this.allowPrivateEndpoints = allowPrivateEndpoints;
        this.requestTimeout = Duration.ofSeconds(Math.max(30, requestTimeoutSeconds));
    }

    public List<McpServerConfiguration> list(String tenantId) {
        return jdbcTemplate.query("""
                        SELECT id, name, endpoint, enabled, auth_type, auth_header_name, secret_hint, secret_ciphertext,
                               allowed_tools, created_at, updated_at
                        FROM mcp_server_configuration WHERE tenant_id = ? ORDER BY name
                        """, (rs, rowNum) -> mapConfiguration(rs), tenantId);
    }

    @Transactional
    public McpServerConfiguration create(String tenantId, McpServerConfigurationRequest request) {
        validate(request);
        validateAuth(request);
        if (!"NONE".equals(authType(request)) && (request.authSecret() == null || request.authSecret().isBlank()))
            throw new IllegalArgumentException("请填写 MCP 认证密钥");
        jdbcTemplate.update("""
                INSERT INTO mcp_server_configuration
                (tenant_id, name, endpoint, enabled, auth_type, auth_header_name, secret_ciphertext, secret_hint, allowed_tools)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))""",
                tenantId, request.name().trim(), normalizeEndpoint(request.endpoint()), request.enabled(), authType(request),
                headerName(request), secretCipher.encrypt(request.authSecret()), secretCipher.hint(request.authSecret()),
                jsonTools(request.allowedTools()));
        return findByName(tenantId, request.name().trim());
    }

    @Transactional
    public McpServerConfiguration update(String tenantId, long id, McpServerConfigurationRequest request) {
        validate(request);
        validateAuth(request);
        McpServerConfiguration existing = find(tenantId, id);
        if (!"NONE".equals(authType(request)) && !request.clearAuthSecret()
                && (request.authSecret() == null || request.authSecret().isBlank()) && !existing.hasSecret())
            throw new IllegalArgumentException("请填写 MCP 认证密钥");
        boolean clearSecret = request.clearAuthSecret() || "NONE".equals(authType(request));
        String secretCiphertext = clearSecret ? null : secretCipher.encrypt(request.authSecret());
        if (jdbcTemplate.update("""
                UPDATE mcp_server_configuration SET name = ?, endpoint = ?, enabled = ?, auth_type = ?,
                auth_header_name = ?, secret_ciphertext = CASE WHEN ? THEN NULL WHEN ? IS NOT NULL THEN ? ELSE secret_ciphertext END,
                secret_hint = CASE WHEN ? THEN NULL WHEN ? IS NOT NULL THEN ? ELSE secret_hint END,
                allowed_tools = COALESCE(CAST(? AS jsonb), allowed_tools), updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?""",
                request.name().trim(), normalizeEndpoint(request.endpoint()), request.enabled(), authType(request), headerName(request),
                clearSecret, secretCiphertext, secretCiphertext, clearSecret, secretCiphertext,
                secretCipher.hint(request.authSecret()), request.allowedTools() == null ? null : jsonTools(request.allowedTools()), id, tenantId) == 0) {
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
            client = connect(config);
            List<McpToolDescriptor> tools = client.listTools().tools().stream().map(this::describeTool).toList();
            return new McpConnectionTestResult(true, "已连接并发现 " + tools.size() + " 个工具", tools);
        } catch (Exception exception) {
            return new McpConnectionTestResult(false, "连接失败：" + safeMessage(exception), List.of());
        } finally {
            if (client != null) client.close();
        }
    }

    public McpTools toolsFor(String tenantId) {
        List<McpSyncClient> clients = new ArrayList<>();
        List<org.springframework.ai.tool.ToolCallback> callbacks = new ArrayList<>();
        for (McpServerConfiguration config : list(tenantId)) {
            if (!config.enabled()) continue;
            McpSyncClient client = null;
            try {
                client = connect(config);
                clients.add(client);
                Set<String> allowed = config.allowedTools() == null ? null : Set.copyOf(config.allowedTools());
                var provider = new SyncMcpToolCallbackProvider((server, tool) -> allowed == null || allowed.contains(tool.name()), List.of(client));
                callbacks.addAll(List.of(provider.getToolCallbacks()));
            } catch (Exception exception) {
                if (client != null) client.close();
            }
        }
        McpToolRegistry.replace(tenantId, callbacks.stream()
                .map(callback -> callback.getToolDefinition().name()).toList());
        if (callbacks.isEmpty()) {
            clients.forEach(McpSyncClient::close);
            return McpTools.EMPTY;
        }
        return new McpTools(callbacks.toArray(org.springframework.ai.tool.ToolCallback[]::new), clients);
    }

    private McpSyncClient connect(McpServerConfiguration config) {
        var transportBuilder = HttpClientSseClientTransport.builder(config.endpoint());
        if (config.hasSecret()) {
            String secret = loadSecret(config.id());
            String header = "BEARER".equals(config.authType()) ? "Authorization" : config.authHeaderName();
            String value = "BEARER".equals(config.authType()) ? "Bearer " + secret : secret;
            transportBuilder.customizeRequest(builder -> builder.header(header, value));
        }
        McpSyncClient client = McpClient.sync(transportBuilder.build())
                .requestTimeout(requestTimeout).initializationTimeout(INITIALIZATION_TIMEOUT)
                .clientInfo(new McpSchema.Implementation("zwx-agent", "0.0.1"))
                .build();
        client.initialize();
        return client;
    }

    private McpServerConfiguration find(String tenantId, long id) {
        List<McpServerConfiguration> values = jdbcTemplate.query("""
                        SELECT id, name, endpoint, enabled, auth_type, auth_header_name, secret_hint, secret_ciphertext,
                               allowed_tools, created_at, updated_at FROM mcp_server_configuration
                        WHERE id = ? AND tenant_id = ?
                        """, (rs, rowNum) -> mapConfiguration(rs), id, tenantId);
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

    private void validateAuth(McpServerConfigurationRequest request) {
        String type = authType(request);
        if (!Set.of("NONE", "BEARER", "HEADER").contains(type)) throw new IllegalArgumentException("不支持的 MCP 认证方式");
        if ("HEADER".equals(type) && (headerName(request) == null || !headerName(request).matches("[A-Za-z0-9-]{1,128}")))
            throw new IllegalArgumentException("自定义认证 Header 名称无效");
        if ("NONE".equals(type) && request.clearAuthSecret()) return;
    }

    private String authType(McpServerConfigurationRequest request) {
        return request.authType() == null || request.authType().isBlank() ? "NONE" : request.authType().trim().toUpperCase();
    }

    private String headerName(McpServerConfigurationRequest request) {
        return request.authHeaderName() == null || request.authHeaderName().isBlank() ? null : request.authHeaderName().trim();
    }

    private String jsonTools(List<String> tools) {
        try { return objectMapper.writeValueAsString(tools == null ? List.of() : tools); }
        catch (Exception exception) { throw new IllegalArgumentException("MCP 工具授权列表无效"); }
    }

    private McpServerConfiguration mapConfiguration(java.sql.ResultSet rs) throws java.sql.SQLException {
        String rawTools = rs.getString("allowed_tools");
        List<String> tools = null;
        if (rawTools != null) try { tools = objectMapper.readValue(rawTools, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception ignored) { tools = List.of(); }
        return new McpServerConfiguration(rs.getLong("id"), rs.getString("name"), rs.getString("endpoint"),
                rs.getBoolean("enabled"), rs.getString("auth_type"), rs.getString("auth_header_name"),
                rs.getString("secret_hint"), rs.getString("secret_ciphertext") != null, tools,
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private String loadSecret(long id) {
        String ciphertext = jdbcTemplate.queryForObject("SELECT secret_ciphertext FROM mcp_server_configuration WHERE id = ?", String.class, id);
        return secretCipher.decrypt(ciphertext);
    }

    private McpToolDescriptor describeTool(McpSchema.Tool tool) {
        Map<String, Object> properties = tool.inputSchema() == null ? Map.of() : tool.inputSchema().properties();
        List<String> required = tool.inputSchema() == null || tool.inputSchema().required() == null ? List.of() : tool.inputSchema().required();
        String schema = "{}";
        if (tool.inputSchema() != null) try { schema = objectMapper.writeValueAsString(tool.inputSchema()); }
        catch (Exception ignored) { schema = "{}"; }
        return new McpToolDescriptor(tool.name(), tool.description(), schema,
                required, properties == null ? List.of() : properties.keySet().stream().toList());
    }

    private String normalizeEndpoint(String endpoint) {
        URI parsed;
        try {
            parsed = URI.create(endpoint == null ? "" : endpoint.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("MCP 地址格式不正确");
        }
        if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("MCP 地址必须是无凭据的 HTTP(S) URL");
        }
        if (!allowPrivateEndpoints) {
            try {
                urlAccessPolicy.validateHttpUrl(endpoint);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("MCP 地址必须是可解析的公网 HTTP(S) URL");
            }
        }
        return parsed.toString().replaceAll("/$", "");
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replaceAll("[\\r\\n]", " ");
    }
}
