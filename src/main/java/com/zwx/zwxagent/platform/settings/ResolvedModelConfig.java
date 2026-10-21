package com.zwx.zwxagent.platform.settings;

/**
 * 生效的模型配置。apiKey 仅供后端构造客户端使用，禁止直接序列化返回前端。
 */
public record ResolvedModelConfig(
        String key,
        String provider,
        String baseUrl,
        String modelName,
        String apiKey,
        String source) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
