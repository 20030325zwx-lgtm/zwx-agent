package com.zwx.zwxagent.platform.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型供应商目录：Provider/Model/Binding 的增删改查、连通性测试与用途解析。
 * 解析顺序：用户绑定 > 系统绑定 > 旧 chat/vision 配置 > 环境变量（由 AppConfigurationService 兜底）。
 * 目录任何变更都会推进 revision，运行开始时冻结到 agent_run，执行中不随全局修改漂移。
 */
@Service
@Slf4j
public class ModelCatalogService {

    public static final String PURPOSE_CHAT = "chat";
    public static final String PURPOSE_VISION = "vision";
    private static final Set<String> PURPOSES = Set.of(PURPOSE_CHAT, PURPOSE_VISION, "embedding");
    private static final Set<String> CAPABILITIES = Set.of("tools", "vision", "embedding");
    private static final long CACHE_TTL_MILLIS = Duration.ofSeconds(30).toMillis();

    public record ModelEntryView(long id, String modelId, String displayName, List<String> capabilities, boolean enabled) {
    }

    public record ProviderView(long id, String scope, String name, String protocol, String baseUrl,
                               boolean enabled, boolean hasApiKey, String apiKeyHint, Boolean lastTestOk,
                               String lastTestMessage, String lastTestAt, List<ModelEntryView> models) {
    }

    public record BindingView(Long providerId, Long modelRowId, String providerName,
                              String modelName, boolean providerEnabled, boolean modelEnabled) {
    }

    public record CatalogView(long revision, List<ProviderView> providers,
                              Map<String, BindingView> userBindings, Map<String, BindingView> systemBindings) {
    }

    public record ResolvedSelection(ResolvedModelConfig config, long revision) {
    }

    public record ProviderRequest(String name, String protocol, String baseUrl, String apiKey,
                                  Boolean enabled, Boolean clearApiKey) {
    }

    public record ModelEntryRequest(String modelId, String displayName, List<String> capabilities, Boolean enabled) {
    }

    public record BindingRequest(String scope, Long providerId, Long modelRowId) {
    }

    public record TestRequest(String baseUrl, String apiKey, String modelId, Boolean clearApiKey) {
    }

    private record ProviderRow(long id, String ownerId, String name, String protocol, String baseUrl,
                               String secretCiphertext, String secretHint, boolean enabled, Boolean lastTestOk,
                               String lastTestMessage, Timestamp lastTestAt) {
    }

    private record ModelRow(long id, long providerId, String modelId, String displayName,
                            List<String> capabilities, boolean enabled) {
    }

    private record BindingRow(long id, String scopeType, String ownerId, String purpose,
                              long providerId, long modelRowId) {
    }

    private record CachedResolve(ResolvedSelection value, long expiresAt) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final ConfigSecretCipher secretCipher;
    private final AppConfigurationService legacyConfiguration;
    private final ModelConfigurationProbe probe;
    private final boolean allowLocalEndpoints;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, CachedResolve> resolveCache = new ConcurrentHashMap<>();

    public ModelCatalogService(JdbcTemplate jdbcTemplate,
                               ConfigSecretCipher secretCipher,
                               AppConfigurationService legacyConfiguration,
                               ModelConfigurationProbe probe,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${app.config.allow-local-model-endpoints:false}") boolean allowLocalEndpoints) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
        this.legacyConfiguration = legacyConfiguration;
        this.probe = probe;
        this.allowLocalEndpoints = allowLocalEndpoints;
    }

    // ---------------------------------------------------------------- 目录读取

    public CatalogView catalog(String userId) {
        List<ProviderRow> rows = jdbcTemplate.query("""
                        SELECT id, owner_id, name, protocol, base_url, secret_ciphertext, secret_hint,
                               enabled, last_test_ok, last_test_message, last_test_at
                        FROM model_provider
                        WHERE owner_id = ? OR owner_id = ''
                        ORDER BY owner_id DESC, id
                        """,
                this::mapProvider, owner(userId));
        Map<Long, List<ModelRow>> modelsByProvider = new LinkedHashMap<>();
        jdbcTemplate.query("""
                        SELECT id, provider_id, model_id, display_name, capabilities::text, enabled
                        FROM model_provider_model
                        ORDER BY id
                        """,
                rs -> {
                    ModelRow row = new ModelRow(rs.getLong("id"), rs.getLong("provider_id"), rs.getString("model_id"),
                            rs.getString("display_name"), parseCapabilities(rs.getString("capabilities")),
                            rs.getBoolean("enabled"));
                    modelsByProvider.computeIfAbsent(row.providerId(), ignored -> new ArrayList<>()).add(row);
                });
        List<ProviderView> providers = rows.stream()
                .map(row -> toView(row, modelsByProvider.getOrDefault(row.id(), List.of())))
                .toList();
        return new CatalogView(revision(), providers,
                bindingsView("USER", owner(userId)), bindingsView("SYSTEM", ""));
    }

    // ---------------------------------------------------------------- 供应商

    @Transactional
    public ProviderView createProvider(String userId, ProviderRequest request) {
        String protocol = normalizeProtocol(request.protocol());
        String baseUrl = normalizeBaseUrl(protocol, request.baseUrl());
        String name = normalizeName(request.name());
        String ciphertext = null;
        String hint = null;
        if (request.apiKey() != null && !request.apiKey().isBlank()) {
            String plain = request.apiKey().trim();
            if (plain.length() > 1024) throw new IllegalArgumentException("API Key 过长");
            ciphertext = secretCipher.encrypt(plain);
            hint = secretCipher.hint(plain);
        }
        Long id = jdbcTemplate.queryForObject("""
                        INSERT INTO model_provider (owner_id, name, protocol, base_url, secret_ciphertext, secret_hint, enabled)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """,
                Long.class, owner(userId), name, protocol, baseUrl, ciphertext, hint,
                request.enabled() == null || request.enabled());
        if ("openai-compatible".equals(protocol) && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalArgumentException("OpenAI 兼容接口需要配置服务地址");
        }
        bumpRevision();
        return providerView(userId, id);
    }

    @Transactional
    public ProviderView updateProvider(String userId, long providerId, ProviderRequest request) {
        ProviderRow provider = requireAccessibleProvider(userId, providerId);
        String protocol = request.protocol() == null ? provider.protocol() : normalizeProtocol(request.protocol());
        String baseUrl = request.baseUrl() == null ? provider.baseUrl()
                : normalizeBaseUrl(protocol, request.baseUrl());
        String name = request.name() == null ? provider.name() : normalizeName(request.name());
        boolean enabled = request.enabled() == null ? provider.enabled() : request.enabled();
        String ciphertext = provider.secretCiphertext();
        String hint = provider.secretHint();
        if (Boolean.TRUE.equals(request.clearApiKey())) {
            ciphertext = null;
            hint = null;
        } else if (request.apiKey() != null && !request.apiKey().isBlank()) {
            String plain = request.apiKey().trim();
            if (plain.length() > 1024) throw new IllegalArgumentException("API Key 过长");
            ciphertext = secretCipher.encrypt(plain);
            hint = secretCipher.hint(plain);
        }
        if ("openai-compatible".equals(protocol) && (baseUrl == null || baseUrl.isBlank())) {
            throw new IllegalArgumentException("OpenAI 兼容接口需要配置服务地址");
        }
        jdbcTemplate.update("""
                        UPDATE model_provider
                        SET name = ?, protocol = ?, base_url = ?, secret_ciphertext = ?, secret_hint = ?,
                            enabled = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                name, protocol, baseUrl, ciphertext, hint, enabled, providerId);
        bumpRevision();
        return providerView(userId, providerId);
    }

    @Transactional
    public void deleteProvider(String userId, long providerId) {
        requireAccessibleProvider(userId, providerId);
        Integer bindings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_binding WHERE provider_id = ?", Integer.class, providerId);
        if (bindings != null && bindings > 0) {
            throw new IllegalArgumentException("该供应商仍被 " + bindings + " 个用途绑定引用，请先在模型绑定中改选其他模型");
        }
        jdbcTemplate.update("DELETE FROM model_provider WHERE id = ?", providerId);
        bumpRevision();
    }

    // ---------------------------------------------------------------- 模型条目

    @Transactional
    public ProviderView addModelEntry(String userId, long providerId, ModelEntryRequest request) {
        requireAccessibleProvider(userId, providerId);
        String modelId = normalizeModelId(request.modelId());
        List<String> capabilities = normalizeCapabilities(request.capabilities());
        jdbcTemplate.update("""
                        INSERT INTO model_provider_model (provider_id, model_id, display_name, capabilities, enabled)
                        VALUES (?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (provider_id, model_id)
                        DO UPDATE SET display_name = EXCLUDED.display_name, capabilities = EXCLUDED.capabilities,
                                      enabled = EXCLUDED.enabled, updated_at = CURRENT_TIMESTAMP
                        """,
                providerId, modelId, normalizeDisplayName(request.displayName(), modelId),
                toJson(capabilities), request.enabled() == null || request.enabled());
        bumpRevision();
        return providerView(userId, providerId);
    }

    @Transactional
    public ProviderView updateModelEntry(String userId, long providerId, long modelRowId, ModelEntryRequest request) {
        requireAccessibleProvider(userId, providerId);
        ModelRow existing = findModelRow(providerId, modelRowId)
                .orElseThrow(() -> new IllegalArgumentException("模型条目不存在"));
        String modelId = request.modelId() == null ? existing.modelId() : normalizeModelId(request.modelId());
        List<String> capabilities = request.capabilities() == null
                ? existing.capabilities() : normalizeCapabilities(request.capabilities());
        jdbcTemplate.update("""
                        UPDATE model_provider_model
                        SET model_id = ?, display_name = ?, capabilities = ?::jsonb, enabled = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ? AND provider_id = ?
                        """,
                modelId, normalizeDisplayName(request.displayName(), modelId), toJson(capabilities),
                request.enabled() == null ? existing.enabled() : request.enabled(), modelRowId, providerId);
        bumpRevision();
        return providerView(userId, providerId);
    }

    @Transactional
    public void deleteModelEntry(String userId, long providerId, long modelRowId) {
        requireAccessibleProvider(userId, providerId);
        Integer bindings = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_binding WHERE model_row_id = ?", Integer.class, modelRowId);
        if (bindings != null && bindings > 0) {
            throw new IllegalArgumentException("该模型仍被 " + bindings + " 个用途绑定引用，请先改选其他模型");
        }
        jdbcTemplate.update("DELETE FROM model_provider_model WHERE id = ? AND provider_id = ?",
                modelRowId, providerId);
        bumpRevision();
    }

    // ---------------------------------------------------------------- 连通性测试

    @Transactional
    public ModelTestResult testProvider(String userId, long providerId, TestRequest draft) {
        ProviderRow provider = requireAccessibleProvider(userId, providerId);
        List<ModelRow> models = modelsOf(providerId);
        String modelId = draft != null && draft.modelId() != null && !draft.modelId().isBlank()
                ? normalizeModelId(draft.modelId())
                : models.stream().filter(ModelRow::enabled).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("该供应商还没有可用模型，请先添加模型"))
                        .modelId();
        String baseUrl = draft != null && draft.baseUrl() != null && !draft.baseUrl().isBlank()
                ? normalizeBaseUrl(provider.protocol(), draft.baseUrl())
                : provider.baseUrl();
        String apiKey;
        if (draft != null && Boolean.TRUE.equals(draft.clearApiKey())) {
            apiKey = null;
        } else if (draft != null && draft.apiKey() != null && !draft.apiKey().isBlank()) {
            apiKey = draft.apiKey().trim();
        } else {
            apiKey = decrypt(provider);
        }
        ResolvedModelConfig config = new ResolvedModelConfig("test", provider.protocol(), baseUrl,
                modelId, apiKey, "TEST");
        ModelTestResult result = probe.test(config);
        jdbcTemplate.update("""
                        UPDATE model_provider
                        SET last_test_ok = ?, last_test_message = ?, last_test_at = CURRENT_TIMESTAMP,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                result.ok(), result.message(), providerId);
        return result;
    }

    // ---------------------------------------------------------------- 用途绑定

    @Transactional
    public CatalogView setBinding(String userId, ConfigurationScope scope, String purpose, BindingRequest request) {
        if (!PURPOSES.contains(purpose)) throw new IllegalArgumentException("未知的模型用途：" + purpose);
        if (request == null || request.providerId() == null || request.modelRowId() == null) {
            throw new IllegalArgumentException("绑定内容不完整");
        }
        ProviderRow provider = requireAccessibleProvider(userId, request.providerId());
        ModelRow model = findModelRow(provider.id(), request.modelRowId())
                .orElseThrow(() -> new IllegalArgumentException("模型条目不属于该供应商"));
        String ownerId = scope == ConfigurationScope.SYSTEM ? "" : owner(userId);
        jdbcTemplate.update("""
                        INSERT INTO model_binding (scope_type, owner_id, purpose, provider_id, model_row_id)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (scope_type, owner_id, purpose)
                        DO UPDATE SET provider_id = EXCLUDED.provider_id, model_row_id = EXCLUDED.model_row_id,
                                      updated_at = CURRENT_TIMESTAMP
                        """,
                scope.name(), ownerId, purpose, provider.id(), model.id());
        bumpRevision();
        return catalog(userId);
    }

    @Transactional
    public CatalogView clearBinding(String userId, ConfigurationScope scope, String purpose) {
        if (!PURPOSES.contains(purpose)) throw new IllegalArgumentException("未知的模型用途：" + purpose);
        String ownerId = scope == ConfigurationScope.SYSTEM ? "" : owner(userId);
        jdbcTemplate.update("DELETE FROM model_binding WHERE scope_type = ? AND owner_id = ? AND purpose = ?",
                scope.name(), ownerId, purpose);
        bumpRevision();
        return catalog(userId);
    }

    // ---------------------------------------------------------------- 运行时解析

    public ResolvedSelection resolve(String userId, String purpose) {
        if (!PURPOSES.contains(purpose)) throw new IllegalArgumentException("未知的模型用途：" + purpose);
        String cacheKey = owner(userId) + "|" + purpose;
        CachedResolve cached = resolveCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) return cached.value();
        ResolvedSelection resolved = new ResolvedSelection(computeResolve(userId, purpose), revision());
        resolveCache.put(cacheKey, new CachedResolve(resolved, now + CACHE_TTL_MILLIS));
        return resolved;
    }

    /**
     * 按模型条目直接解析：对话输入框里切换模型时使用。
     * 只允许本人供应商或系统供应商，且供应商与模型都必须处于启用状态。
     */
    public ResolvedSelection resolveModelRow(String userId, long modelRowId) {
        Long providerId = jdbcTemplate.query(
                "SELECT provider_id FROM model_provider_model WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                modelRowId);
        if (providerId == null) throw new IllegalArgumentException("模型不存在或已被删除");
        ProviderRow provider = requireAccessibleProvider(userId, providerId);
        if (!provider.enabled()) throw new IllegalArgumentException("所选模型的供应商已停用");
        ModelRow model = findModelRow(provider.id(), modelRowId)
                .orElseThrow(() -> new IllegalArgumentException("模型不存在或已被删除"));
        if (!model.enabled()) throw new IllegalArgumentException("所选模型已停用");
        String baseUrl = "dashscope".equals(provider.protocol()) && isBlank(provider.baseUrl())
                ? null : provider.baseUrl();
        String source = provider.ownerId().isEmpty() ? "SYSTEM" : "USER";
        return new ResolvedSelection(new ResolvedModelConfig(PURPOSE_CHAT, provider.protocol(), baseUrl,
                model.modelId(), decrypt(provider), source), revision());
    }

    private ResolvedModelConfig computeResolve(String userId, String purpose) {
        Optional<BindingRow> userBinding = findBinding(ConfigurationScope.USER, owner(userId), purpose);
        Optional<ResolvedModelConfig> user = bindingConfig(userBinding, purpose, "USER");
        if (user.isPresent()) return user.get();
        Optional<BindingRow> systemBinding = findBinding(ConfigurationScope.SYSTEM, "", purpose);
        Optional<ResolvedModelConfig> system = bindingConfig(systemBinding, purpose, "SYSTEM");
        if (system.isPresent()) return system.get();
        // 旧 chat/vision 配置与环境变量兜底；source 保留旧语义（USER/SYSTEM/ENVIRONMENT）
        return legacyConfiguration.resolve(userId, purpose);
    }

    private Optional<ResolvedModelConfig> bindingConfig(Optional<BindingRow> binding, String purpose, String source) {
        if (binding.isEmpty()) return Optional.empty();
        ProviderRow provider = findProvider(binding.get().providerId()).orElse(null);
        if (provider == null || !provider.enabled()) return Optional.empty();
        ModelRow model = findModelRow(provider.id(), binding.get().modelRowId()).orElse(null);
        if (model == null || !model.enabled()) return Optional.empty();
        String baseUrl = "dashscope".equals(provider.protocol()) && isBlank(provider.baseUrl())
                ? null : provider.baseUrl();
        return Optional.of(new ResolvedModelConfig(purpose, provider.protocol(), baseUrl,
                model.modelId(), decrypt(provider), source));
    }

    /**
     * 运行开始时冻结模型身份：评测回放、断线恢复据此重建客户端，不随全局修改漂移。
     */
    public void attachToRun(String runId, ResolvedSelection selection) {
        if (runId == null || selection == null) return;
        try {
            jdbcTemplate.update("""
                            UPDATE agent_run SET model_revision = ?, model_provider = ?, model_name = ?
                            WHERE id = ?
                            """,
                    selection.revision(), selection.config().provider(), selection.config().modelName(), runId);
        } catch (RuntimeException exception) {
            log.warn("记录运行模型身份失败：runId={}", runId, exception);
        }
    }

    // ---------------------------------------------------------------- 内部

    private long revision() {
        Long value = jdbcTemplate.queryForObject("SELECT revision FROM model_catalog_state WHERE id = 1", Long.class);
        return value == null ? 0L : value;
    }

    private void bumpRevision() {
        jdbcTemplate.update("""
                        UPDATE model_catalog_state
                        SET revision = revision + 1, updated_at = CURRENT_TIMESTAMP
                        WHERE id = 1
                        """);
        resolveCache.clear();
    }

    private Optional<BindingRow> findBinding(ConfigurationScope scope, String ownerId, String purpose) {
        List<BindingRow> rows = jdbcTemplate.query("""
                        SELECT id, scope_type, owner_id, purpose, provider_id, model_row_id
                        FROM model_binding
                        WHERE scope_type = ? AND owner_id = ? AND purpose = ?
                        """,
                (rs, rowNum) -> new BindingRow(rs.getLong("id"), rs.getString("scope_type"), rs.getString("owner_id"),
                        rs.getString("purpose"), rs.getLong("provider_id"), rs.getLong("model_row_id")),
                scope.name(), ownerId, purpose);
        return rows.stream().findFirst();
    }

    private Optional<ProviderRow> findProvider(long providerId) {
        List<ProviderRow> rows = jdbcTemplate.query("""
                        SELECT id, owner_id, name, protocol, base_url, secret_ciphertext, secret_hint,
                               enabled, last_test_ok, last_test_message, last_test_at
                        FROM model_provider WHERE id = ?
                        """,
                this::mapProvider, providerId);
        return rows.stream().findFirst();
    }

    private List<ModelRow> modelsOf(long providerId) {
        return jdbcTemplate.query("""
                        SELECT id, provider_id, model_id, display_name, capabilities::text, enabled
                        FROM model_provider_model WHERE provider_id = ? ORDER BY id
                        """,
                (rs, rowNum) -> new ModelRow(rs.getLong("id"), rs.getLong("provider_id"), rs.getString("model_id"),
                        rs.getString("display_name"), parseCapabilities(rs.getString("capabilities")),
                        rs.getBoolean("enabled")),
                providerId);
    }

    private Optional<ModelRow> findModelRow(long providerId, long modelRowId) {
        return modelsOf(providerId).stream().filter(row -> row.id() == modelRowId).findFirst();
    }

    private ProviderRow requireAccessibleProvider(String userId, long providerId) {
        ProviderRow provider = findProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("供应商不存在"));
        // 系统级供应商（owner 为空）的管理员校验由 Controller 完成；这里保证个人供应商不被越权访问
        if (!provider.ownerId().isEmpty() && !provider.ownerId().equals(owner(userId))) {
            throw new IllegalArgumentException("无权操作该供应商");
        }
        return provider;
    }

    private ProviderView providerView(String userId, long providerId) {
        ProviderRow row = findProvider(providerId).orElseThrow(() -> new IllegalArgumentException("供应商不存在"));
        return toView(row, modelsOf(providerId));
    }

    private ProviderView toView(ProviderRow row, List<ModelRow> models) {
        return new ProviderView(row.id(), row.ownerId().isEmpty() ? "SYSTEM" : "USER", row.name(),
                row.protocol(), row.baseUrl(), row.enabled(),
                row.secretCiphertext() != null && !row.secretCiphertext().isBlank(), row.secretHint(),
                row.lastTestOk(), row.lastTestMessage(),
                row.lastTestAt() == null ? null : row.lastTestAt().toInstant().toString(),
                models.stream().map(model -> new ModelEntryView(model.id(), model.modelId(),
                        model.displayName() == null ? model.modelId() : model.displayName(),
                        model.capabilities(), model.enabled())).toList());
    }

    private Map<String, BindingView> bindingsView(String scopeType, String ownerId) {
        Map<String, BindingView> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                        SELECT b.purpose, b.provider_id, b.model_row_id, p.name AS provider_name, p.enabled AS provider_enabled,
                               m.model_id, m.enabled AS model_enabled
                        FROM model_binding b
                        JOIN model_provider p ON p.id = b.provider_id
                        JOIN model_provider_model m ON m.id = b.model_row_id
                        WHERE b.scope_type = ? AND b.owner_id = ?
                        """,
                rs -> {
                    String purpose = rs.getString("purpose");
                    result.put(purpose, new BindingView(rs.getLong("provider_id"), rs.getLong("model_row_id"),
                            rs.getString("provider_name"), rs.getString("model_id"),
                            rs.getBoolean("provider_enabled"), rs.getBoolean("model_enabled")));
                }, scopeType, ownerId);
        return result;
    }

    private ProviderRow mapProvider(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ProviderRow(rs.getLong("id"), rs.getString("owner_id"), rs.getString("name"),
                rs.getString("protocol"), rs.getString("base_url"), rs.getString("secret_ciphertext"),
                rs.getString("secret_hint"), rs.getBoolean("enabled"),
                rs.getObject("last_test_ok") == null ? null : rs.getBoolean("last_test_ok"),
                rs.getString("last_test_message"),
                rs.getTimestamp("last_test_at"));
    }

    private List<String> parseCapabilities(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<?> raw = objectMapper.readValue(json, List.class);
            return raw.stream().map(String::valueOf).filter(CAPABILITIES::contains).toList();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String toJson(List<String> capabilities) {
        try {
            return objectMapper.writeValueAsString(capabilities);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private String normalizeProtocol(String protocol) {
        String value = trimToNull(protocol);
        if (value == null) throw new IllegalArgumentException("请选择协议类型");
        value = value.toLowerCase(java.util.Locale.ROOT);
        if (!"dashscope".equals(value) && !"openai-compatible".equals(value)) {
            throw new IllegalArgumentException("暂不支持的模型供应商协议：" + protocol);
        }
        return value;
    }

    private String normalizeBaseUrl(String protocol, String baseUrl) {
        String value = trimToNull(baseUrl);
        if (value == null) {
            if ("openai-compatible".equals(protocol)) throw new IllegalArgumentException("OpenAI 兼容接口需要配置服务地址");
            return null;
        }
        // 桌面等本机部署场景允许显式配置回环/内网模型地址；网页部署保持公网出站限制
        if (allowLocalEndpoints && isLocalEndpoint(value)) {
            String normalized = value.replaceAll("/+$", "");
            if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                throw new IllegalArgumentException("模型服务地址必须是 HTTP(S) URL");
            }
            return normalized;
        }
        return legacyConfiguration.validateAndNormalizeBaseUrl(value);
    }

    private boolean isLocalEndpoint(String url) {
        try {
            java.net.URI parsed = java.net.URI.create(url);
            String host = parsed.getHost();
            if (host == null) return false;
            if ("localhost".equalsIgnoreCase(host)) return true;
            java.net.InetAddress address = java.net.InetAddress.getByName(host);
            return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isAnyLocalAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private String normalizeName(String name) {
        String value = trimToNull(name);
        if (value == null) throw new IllegalArgumentException("请填写供应商名称");
        if (value.length() > 64) throw new IllegalArgumentException("供应商名称不能超过 64 个字符");
        return value;
    }

    private String normalizeModelId(String modelId) {
        String value = trimToNull(modelId);
        if (value == null) throw new IllegalArgumentException("请填写模型 ID");
        if (value.length() > 128) throw new IllegalArgumentException("模型 ID 不能超过 128 个字符");
        return value;
    }

    private String normalizeDisplayName(String displayName, String modelId) {
        String value = trimToNull(displayName);
        return value == null ? modelId : (value.length() > 128 ? value.substring(0, 128) : value);
    }

    private List<String> normalizeCapabilities(List<String> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return List.of();
        List<String> normalized = capabilities.stream()
                .filter(java.util.Objects::nonNull)
                .map(item -> item.trim().toLowerCase(java.util.Locale.ROOT))
                .filter(item -> !item.isEmpty())
                .toList();
        for (String item : normalized) {
            if (!CAPABILITIES.contains(item)) {
                throw new IllegalArgumentException("暂不支持的能力声明：" + item);
            }
        }
        return normalized.stream().distinct().toList();
    }

    private String decrypt(ProviderRow provider) {
        if (provider.secretCiphertext() == null || provider.secretCiphertext().isBlank()) return null;
        try {
            return secretCipher.decrypt(provider.secretCiphertext());
        } catch (RuntimeException exception) {
            log.warn("无法解密供应商 API Key；providerId={}", provider.id());
            return null;
        }
    }

    private String owner(String userId) {
        return userId == null ? "" : userId.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
