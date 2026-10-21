package com.zwx.zwxagent.configuration;

public record ModelTestResult(
        boolean ok,
        String message,
        String provider,
        String modelName,
        String source,
        long latencyMs) {
}
