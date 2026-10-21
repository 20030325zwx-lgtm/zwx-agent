package com.zwx.zwxagent.configuration;

public record ModelConfigurationView(
        String key,
        String label,
        String description,
        boolean overridable,
        ModelConfigView effective,
        ModelConfigView userOverride,
        ModelConfigView systemDefault) {

    public record ModelConfigView(
            String provider,
            String baseUrl,
            String modelName,
            String secretHint,
            boolean hasSecret,
            boolean enabled,
            boolean present,
            String source) {
    }
}
