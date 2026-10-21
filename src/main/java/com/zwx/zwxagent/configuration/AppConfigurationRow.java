package com.zwx.zwxagent.configuration;

import java.time.Instant;

public record AppConfigurationRow(
        long id,
        String scopeType,
        String ownerId,
        String configType,
        String configKey,
        String provider,
        String baseUrl,
        String modelName,
        String secretCiphertext,
        String secretHint,
        boolean enabled,
        String extra,
        Instant createdAt,
        Instant updatedAt) {
}
