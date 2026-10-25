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
        // 审批监听覆盖 super（UI 上传的实际范围）与 test（n8n ingestion 试点范围）；
        // 审批决定仍由 ReviewAccessGuard 拦截，WORKFLOW 永远无审批权。
        boolean knownAgentKey = "test".equals(agentKey) || "super".equals(agentKey);
        if (role != Role.WORKFLOW || !"default".equals(tenantId) || !knownAgentKey) {
            throw new org.springframework.security.access.AccessDeniedException("Knowledge workflow access denied");
        }
    }
}
