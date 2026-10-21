package com.zwx.zwxagent.platform.security;

public record AppUser(String id, String tenantId, String username, String passwordHash, Role role, String status) {
}
