package com.zwx.zwxagent.platform.settings;

import com.zwx.zwxagent.capability.tools.UrlAccessPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型配置解析：用户覆盖 > 系统默认 > 环境变量。
 * 所有写操作需要由 Controller 完成身份与管理员权限校验。
 */
@Service
@Slf4j
public class AppConfigurationService {

    public static final String CHAT = "chat";
    public static final String VISION = "vision";
    public static final String PROVIDER_DASHSCOPE = "dashscope";
    public static final String PROVIDER_OPENAI_COMPATIBLE = "openai-compatible";

    private static final Set<String> SUPPORTED_PROVIDERS = Set.of(PROVIDER_DASHSCOPE, PROVIDER_OPENAI_COMPATIBLE);

    private record Descriptor(String key, String label, String description, boolean overridable, String envModel) {
    }

    private record CachedResolve(ResolvedModelConfig value, long expiresAt) {
    }

    private final Map<String, Descriptor> descriptors = new LinkedHashMap<>();

    private final JdbcTemplate jdbcTemplate;
    private final ConfigSecretCipher secretCipher;
    private final UrlAccessPolicy urlAccessPolicy;
    private final String envApiKey;
    private final String envBaseUrl;
    private final Set<String> modelHostAllowlist;
    private final long cacheTtlMillis;
    private final ConcurrentHashMap<String, CachedResolve> resolveCache = new ConcurrentHashMap<>();

    public AppConfigurationService(JdbcTemplate jdbcTemplate,
                                   ConfigSecretCipher secretCipher,
                                   UrlAccessPolicy urlAccessPolicy,
                                   @Value("${spring.ai.dashscope.api-key:}") String envApiKey,
                                   @Value("${spring.ai.dashscope.chat.options.model:qwen-plus}") String envChatModel,
                                   @Value("${app.love.vision-model:qwen3-vl-plus}") String envVisionModel,
                                   @Value("${app.config.dashscope-base-url:https://dashscope.aliyuncs.com}") String envBaseUrl,
                                   @Value("${app.config.model-host-allowlist:dashscope.aliyuncs.com,api.openai.com}") String hostAllowlist,
                                   @Value("${app.config.cache-ttl-seconds:30}") long cacheTtlSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.secretCipher = secretCipher;
        this.urlAccessPolicy = urlAccessPolicy;
        this.envApiKey = normalizeApiKey(envApiKey);
        this.envBaseUrl = envBaseUrl;
        this.modelHostAllowlist = parseHostAllowlist(hostAllowlist);
        this.cacheTtlMillis = Duration.ofSeconds(Math.max(0, cacheTtlSeconds)).toMillis();
        descriptors.put(CHAT, new Descriptor(CHAT, "对话模型",
                "情感分析、旅游规划、功能测试与超级智能体的默认文本模型。", true, envChatModel));
        descriptors.put(VISION, new Descriptor(VISION, "视觉模型",
                "情感图片理解与多模态对话使用的视觉模型。", true, envVisionModel));
    }

    public List<ModelConfigurationView> list(String userId) {
        List<ModelConfigurationView> result = new ArrayList<>();
        descriptors.values().forEach(descriptor -> result.add(view(userId, descriptor.key())));
        return result;
    }

    public ModelConfigurationView view(String userId, String key) {
        Descriptor descriptor = descriptor(key);
        ResolvedModelConfig effective = resolve(userId, key);
        return new ModelConfigurationView(descriptor.key(), descriptor.label(), descriptor.description(),
                descriptor.overridable(), toView(effective, cipherHint(effective.apiKey())),
                findRow(ConfigurationScope.USER, owner(userId), key).map(this::toView).orElse(null),
                findRow(ConfigurationScope.SYSTEM, "", key).map(this::toView).orElse(null));
    }

    public ResolvedModelConfig resolve(String userId, String key) {
        Descriptor descriptor = descriptor(key);
        String cacheKey = owner(userId) + "|" + key;
        CachedResolve cached = resolveCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) return cached.value();
        ResolvedModelConfig resolved = computeResolve(userId, descriptor);
        if (cacheTtlMillis > 0) {
            resolveCache.put(cacheKey, new CachedResolve(resolved, now + cacheTtlMillis));
        }
        return resolved;
    }

    private ResolvedModelConfig computeResolve(String userId, Descriptor descriptor) {
        AppConfigurationRow userRow = findRow(ConfigurationScope.USER, owner(userId), descriptor.key()).orElse(null);
        AppConfigurationRow systemRow = findRow(ConfigurationScope.SYSTEM, "", descriptor.key()).orElse(null);
        boolean userActive = hasOverride(userRow);
        boolean systemActive = hasOverride(systemRow);

        String provider = firstNonBlank(
                userActive ? userRow.provider() : null,
                systemActive ? systemRow.provider() : null,
                PROVIDER_DASHSCOPE);
        String baseUrl = firstNonBlank(
                userActive ? userRow.baseUrl() : null,
                systemActive ? systemRow.baseUrl() : null,
                PROVIDER_OPENAI_COMPATIBLE.equalsIgnoreCase(provider) ? null : envBaseUrl);
        String modelName = firstNonBlank(
                userActive ? userRow.modelName() : null,
                systemActive ? systemRow.modelName() : null,
                descriptor.envModel());
        String apiKey = firstNonBlank(
                userActive ? safeDecrypt(userRow) : null,
                systemActive ? safeDecrypt(systemRow) : null,
                envApiKey);
        String source = userActive ? "USER" : systemActive ? "SYSTEM" : "ENVIRONMENT";
        return new ResolvedModelConfig(descriptor.key(), provider, baseUrl, modelName, apiKey, source);
    }

    @Transactional
    public ModelConfigurationView save(String userId, ConfigurationScope scope, String key, ModelConfigurationRequest request) {
        Descriptor descriptor = descriptor(key);
        if (!descriptor.overridable()) {
            throw new IllegalArgumentException("该配置项不允许覆盖");
        }
        if (scope == ConfigurationScope.USER && owner(userId).isBlank()) {
            throw new IllegalArgumentException("缺少用户身份");
        }
        if (request == null) throw new IllegalArgumentException("配置内容不能为空");

        String provider = normalizeProvider(request.provider());
        String baseUrl = normalizeBaseUrl(request.baseUrl());
        String modelName = normalizeModel(request.modelName());
        boolean enabled = request.enabled() == null || request.enabled();
        boolean clearApiKey = Boolean.TRUE.equals(request.clearApiKey());
        String ownerId = scope == ConfigurationScope.SYSTEM ? "" : owner(userId);
        AppConfigurationRow existing = findRow(scope, ownerId, key).orElse(null);
        String ciphertext = existing == null ? null : existing.secretCiphertext();
        String hint = existing == null ? null : existing.secretHint();
        String plainKey = request.apiKey() == null ? null : request.apiKey().trim();
        if (clearApiKey) {
            ciphertext = null;
            hint = null;
        } else if (plainKey != null && !plainKey.isBlank()) {
            if (plainKey.length() > 1024) throw new IllegalArgumentException("API Key 过长");
            ciphertext = secretCipher.encrypt(plainKey);
            hint = secretCipher.hint(plainKey);
        }
        if (provider == null && baseUrl == null && modelName == null && ciphertext == null) {
            throw new IllegalArgumentException("请至少填写模型、服务地址或 API Key 中的一项");
        }
        jdbcTemplate.update("""
                        INSERT INTO app_configuration
                            (scope_type, owner_id, config_type, config_key, provider, base_url, model_name,
                             secret_ciphertext, secret_hint, enabled)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (scope_type, owner_id, config_type, config_key)
                        DO UPDATE SET provider = EXCLUDED.provider, base_url = EXCLUDED.base_url,
                                      model_name = EXCLUDED.model_name, secret_ciphertext = EXCLUDED.secret_ciphertext,
                                      secret_hint = EXCLUDED.secret_hint, enabled = EXCLUDED.enabled,
                                      updated_at = CURRENT_TIMESTAMP
                        """,
                scope.name(), ownerId, ConfigurationType.MODEL.name(), key,
                provider, baseUrl, modelName, ciphertext, hint, enabled);
        invalidate();
        return view(userId, key);
    }

    @Transactional
    public void reset(String userId, ConfigurationScope scope, String key) {
        descriptor(key);
        String ownerId = scope == ConfigurationScope.SYSTEM ? "" : owner(userId);
        jdbcTemplate.update("DELETE FROM app_configuration WHERE scope_type = ? AND owner_id = ? AND config_type = ? AND config_key = ?",
                scope.name(), ownerId, ConfigurationType.MODEL.name(), key);
        invalidate();
    }

    public ResolvedModelConfig resolveSystem(String key) {
        Descriptor descriptor = descriptor(key);
        AppConfigurationRow systemRow = findRow(ConfigurationScope.SYSTEM, "", key).orElse(null);
        if (!hasOverride(systemRow)) return resolve("", key);
        String provider = firstNonBlank(systemRow.provider(), PROVIDER_DASHSCOPE);
        String baseUrl = firstNonBlank(systemRow.baseUrl(),
                PROVIDER_OPENAI_COMPATIBLE.equalsIgnoreCase(provider) ? null : envBaseUrl);
        String modelName = firstNonBlank(systemRow.modelName(), descriptor.envModel());
        String apiKey = firstNonBlank(safeDecrypt(systemRow), envApiKey);
        return new ResolvedModelConfig(key, provider, baseUrl, modelName, apiKey,
                systemRow.enabled() ? "SYSTEM" : "ENVIRONMENT");
    }

    private Descriptor descriptor(String key) {
        Descriptor descriptor = descriptors.get(key);
        if (descriptor == null) throw new IllegalArgumentException("未知的模型配置项：" + key);
        return descriptor;
    }

    private Optional<AppConfigurationRow> findRow(ConfigurationScope scope, String ownerId, String key) {
        List<AppConfigurationRow> rows = jdbcTemplate.query("""
                        SELECT id, scope_type, owner_id, config_type, config_key, provider, base_url, model_name,
                               secret_ciphertext, secret_hint, enabled, extra::text, created_at, updated_at
                        FROM app_configuration
                        WHERE scope_type = ? AND owner_id = ? AND config_type = ? AND config_key = ?
                        """,
                (rs, rowNum) -> new AppConfigurationRow(
                        rs.getLong("id"), rs.getString("scope_type"), rs.getString("owner_id"),
                        rs.getString("config_type"), rs.getString("config_key"), rs.getString("provider"),
                        rs.getString("base_url"), rs.getString("model_name"), rs.getString("secret_ciphertext"),
                        rs.getString("secret_hint"), rs.getBoolean("enabled"), rs.getString("extra"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                scope.name(), ownerId, ConfigurationType.MODEL.name(), key);
        return rows.stream().findFirst();
    }

    private ModelConfigurationView.ModelConfigView toView(AppConfigurationRow row) {
        if (row == null) return null;
        return new ModelConfigurationView.ModelConfigView(row.provider(), row.baseUrl(), row.modelName(),
                row.secretHint(), row.secretCiphertext() != null && !row.secretCiphertext().isBlank(),
                row.enabled(), true, row.scopeType());
    }

    private ModelConfigurationView.ModelConfigView toView(ResolvedModelConfig config, String hint) {
        return new ModelConfigurationView.ModelConfigView(config.provider(), config.baseUrl(), config.modelName(),
                hint, config.hasApiKey(), true, true, config.source());
    }

    private String cipherHint(String plaintext) {
        try {
            return secretCipher.hint(plaintext);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private String safeDecrypt(AppConfigurationRow row) {
        try {
            return secretCipher.decrypt(row.secretCiphertext());
        } catch (RuntimeException exception) {
            log.warn("无法解密模型配置密钥；configKey={}, scope={}", row.configKey(), row.scopeType());
            return null;
        }
    }

    private boolean hasOverride(AppConfigurationRow row) {
        if (row == null || !row.enabled()) return false;
        return row.provider() != null || row.baseUrl() != null || row.modelName() != null
                || (row.secretCiphertext() != null && !row.secretCiphertext().isBlank());
    }

    private String normalizeProvider(String provider) {
        String value = trimToNull(provider);
        if (value == null) return null;
        value = value.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_PROVIDERS.contains(value)) {
            throw new IllegalArgumentException("暂不支持的模型供应商：" + provider);
        }
        return value;
    }

    private String normalizeBaseUrl(String baseUrl) {
        String value = trimToNull(baseUrl);
        if (value == null) return null;
        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("模型服务地址格式不正确");
        }
        if (!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme()))
                || parsed.getHost() == null || parsed.getUserInfo() != null || parsed.getFragment() != null) {
            throw new IllegalArgumentException("模型服务地址必须是无凭据的公网 HTTP(S) URL");
        }
        if (!isAllowlistedHost(parsed.getHost())) {
            try {
                urlAccessPolicy.validateHttpUrl(value);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("模型服务地址必须是可解析的公网 HTTP(S) URL");
            }
        }
        return parsed.toString().replaceAll("/+$", "");
    }

    private boolean isAllowlistedHost(String host) {
        String normalized = host == null ? "" : host.toLowerCase(Locale.ROOT);
        return modelHostAllowlist.stream()
                .anyMatch(item -> normalized.equals(item) || normalized.endsWith("." + item));
    }

    private Set<String> parseHostAllowlist(String value) {
        if (value == null || value.isBlank()) return Set.of();
        java.util.LinkedHashSet<String> hosts = new java.util.LinkedHashSet<>();
        for (String item : value.split(",")) {
            String host = item.trim().toLowerCase(Locale.ROOT);
            if (!host.isBlank()) hosts.add(host);
        }
        return Set.copyOf(hosts);
    }

    private String normalizeModel(String modelName) {
        String value = trimToNull(modelName);
        if (value == null) return null;
        if (value.length() > 128) throw new IllegalArgumentException("模型名称不能超过 128 个字符");
        return value;
    }

    private String normalizeApiKey(String apiKey) {
        String value = trimToNull(apiKey);
        if (value == null || "your-api-key".equalsIgnoreCase(value)) return null;
        return value;
    }

    private String owner(String userId) {
        return userId == null ? "" : userId.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void invalidate() {
        resolveCache.clear();
    }
}
