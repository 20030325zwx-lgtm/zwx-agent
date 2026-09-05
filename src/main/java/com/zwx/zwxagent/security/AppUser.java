package com.zwx.zwxagent.security;

public record AppUser(String id, String tenantId, String username, String passwordHash, Role role, String status) {
}
