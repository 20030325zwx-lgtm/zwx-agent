package com.zwx.zwxagent.security;

public record CurrentActor(String userId, String tenantId, String username, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException("Administrator role required");
        }
    }
}
