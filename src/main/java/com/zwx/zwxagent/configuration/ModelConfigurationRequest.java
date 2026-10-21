package com.zwx.zwxagent.configuration;

public record ModelConfigurationRequest(
        String provider,
        String baseUrl,
        String modelName,
        String apiKey,
        Boolean clearApiKey,
        Boolean enabled) {
}
