package com.zwx.zwxagent.platform.security;

public record CurrentActor(String userId, String tenantId, String username, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public void requireAdmin() {
        if (!isAdmin()) {
            throw new org.springframework.security.access.AccessDeniedException("Administrator role required");
        }
    }

    public void requireKnowledgeWorkflowAccess(String agentKey) {
        if (isAdmin()) return;
        if (role != Role.WORKFLOW || !"default".equals(tenantId) || !"test".equals(agentKey)) {
            throw new org.springframework.security.access.AccessDeniedException("Knowledge workflow access denied");
        }
    }
}
