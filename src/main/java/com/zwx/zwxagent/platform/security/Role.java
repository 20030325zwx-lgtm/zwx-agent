package com.zwx.zwxagent.platform.security;

public enum Role {
    USER,
    ADMIN,
    WORKFLOW,
    REVIEWER;

    public String authority() {
        return "ROLE_" + name();
    }

    public static Role from(String value) {
        if (value == null || value.isBlank()) return USER;
        try {
            return Role.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return USER;
        }
    }
}
