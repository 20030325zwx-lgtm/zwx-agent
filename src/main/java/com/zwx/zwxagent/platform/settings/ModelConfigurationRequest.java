package com.zwx.zwxagent.platform.settings;

public record ModelConfigurationRequest(
        String provider,
        String baseUrl,
        String modelName,
        String apiKey,
        Boolean clearApiKey,
        Boolean enabled) {
}
