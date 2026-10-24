package com.zwx.zwxagent.platform.security;

import com.zwx.zwxagent.knowledge.document.AgentReviewerAssignmentService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * 审批动作访问守卫：ADMIN 全范围兜底；REVIEWER 仅限分配表授权的 (tenant, agent) 范围。
 * USER / WORKFLOW 一律拒绝——工作流服务身份不再允许替人做审批决定。
 */
@Component
public class ReviewAccessGuard {

    private final AgentReviewerAssignmentService assignmentService;

    public ReviewAccessGuard(AgentReviewerAssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    public void requireReviewAccess(CurrentActor actor, String agentKey) {
        if (actor.isAdmin()) return;
        if (actor.role() != Role.REVIEWER || !assignmentService.isAssigned(actor.tenantId(), agentKey, actor.userId())) {
            throw new AccessDeniedException("Review access denied");
        }
    }
}
