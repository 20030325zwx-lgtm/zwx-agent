package com.zwx.zwxagent.platform.settings;

public record ModelTestResult(
        boolean ok,
        String message,
        String provider,
        String modelName,
        String source,
        long latencyMs) {
}
