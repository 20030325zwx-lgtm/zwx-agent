package com.zwx.zwxagent.agent.execution;

import com.zwx.zwxagent.agent.graph.GraphCheckpoint;
import com.zwx.zwxagent.agent.graph.ManusGraphOrchestrator;
import com.zwx.zwxagent.capability.tools.ToolFactory;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.chat.conversation.AgentConversationService;
import com.zwx.zwxagent.chat.conversation.ApplicationInstanceRegistry;
import com.zwx.zwxagent.chat.conversation.ConversationLockManager;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import com.zwx.zwxagent.platform.workspace.ProjectExecutionLockService;
import com.zwx.zwxagent.platform.workspace.RunWorkspaceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

import jakarta.annotation.Resource;

/**
 * 工作智能体整改 A2：engine_version=2 的持久化派发器（方案 §4.1/§4.4/§9）。
 * <ul>
 *   <li>认领：AgentRunStateService.claim 单语句 SKIP LOCKED + fencing 递增；事务外执行；</li>
 *   <li>会话并发槽：派发时尝试会话锁，占用中则原样退回队列（不消耗尝试次数）；</li>
 *   <li>执行：复用既有图编排（runStream），SSE 仅为观察通道，本类不管理 emitter，断连不停任务；</li>
 *   <li>控制：control_state 由 RunContext.stopCheck 在图节点安全点轮询应用；</li>
 *   <li>终态：completionHandler 经 AgentRunStateService.complete CAS 回写，拒绝旧执行器迟到写。</li>
 * </ul>
 * v2 首轮装配范围：默认模型 + 项目工具 + 会话历史；MCP 工具与知识检索随 B1 前端迁移补齐。
 */
@Slf4j
@Service
public class AgentRunDispatcher {

    private static final String SUPER_AGENT_KEY = "super";

    private final AgentRunStateService stateService;
    private final AgentRunService agentRunService;
    private final ManusGraphOrchestrator orchestrator;
    private final ToolFactory toolFactory;
    private final DynamicChatModelFactory chatModelFactory;
    private final ModelCatalogService modelCatalogService;
    private final AgentConversationService conversationService;
    private final ConversationLockManager conversationLockManager;
    private final ProjectExecutionLockService projectExecutionLockService;
    private final RunWorkspaceService runWorkspaceService;
    private final ApplicationInstanceRegistry instanceRegistry;
    private final Executor agentExecutor;
    private final boolean enabled;
    private final Semaphore dispatchSlots;

    public AgentRunDispatcher(AgentRunStateService stateService,
                              AgentRunService agentRunService,
                              ManusGraphOrchestrator orchestrator,
                              ToolFactory toolFactory,
                              DynamicChatModelFactory chatModelFactory,
                              ModelCatalogService modelCatalogService,
                              AgentConversationService conversationService,
                              ConversationLockManager conversationLockManager,
                              ProjectExecutionLockService projectExecutionLockService,
                              RunWorkspaceService runWorkspaceService,
                              ApplicationInstanceRegistry instanceRegistry,
                              @org.springframework.beans.factory.annotation.Qualifier("agentExecutor") Executor agentExecutor,
                              @Value("${app.agent.dispatch.enabled:true}") boolean enabled,
                              @Value("${app.agent.dispatch.max-inflight:2}") int maxInflight) {
        this.stateService = stateService;
        this.agentRunService = agentRunService;
        this.orchestrator = orchestrator;
        this.toolFactory = toolFactory;
        this.chatModelFactory = chatModelFactory;
        this.modelCatalogService = modelCatalogService;
        this.conversationService = conversationService;
        this.conversationLockManager = conversationLockManager;
        this.projectExecutionLockService = projectExecutionLockService;
        this.runWorkspaceService = runWorkspaceService;
        this.instanceRegistry = instanceRegistry;
        this.agentExecutor = agentExecutor;
        this.enabled = enabled;
        this.dispatchSlots = new Semaphore(Math.max(1, maxInflight));
    }

    @Scheduled(fixedDelay = 1500, initialDelay = 20_000)
    public void dispatchPending() {
        if (!enabled || !dispatchSlots.tryAcquire()) return;
        try {
            claimAndExecute();
        } catch (Exception exception) {
            log.error("派发循环异常", exception);
        } finally {
            dispatchSlots.release();
        }
    }

    private void claimAndExecute() {
        String ownerInstance = instanceRegistry.currentInstanceId();
        AgentRunStateService.ClaimedRun claimed = stateService.claim(ownerInstance).orElse(null);
        if (claimed == null) return;
        AgentRun run = agentRunService.findByIdInTenant(claimed.tenantId(), claimed.id()).orElse(null);
        if (run == null) {
            log.warn("认领的 run 不存在，已回读失败；runId={}", claimed.id());
            return;
        }
        var lease = conversationLockManager.tryAcquire(
                claimed.tenantId() + ":" + claimed.userId() + ":" + SUPER_AGENT_KEY + ":" + claimed.conversationId());
        if (lease == null) {
            stateService.requeueClaim(claimed.id(), ownerInstance, claimed.fencingToken(), 15);
            log.info("会话执行槽占用，run 退回队列；runId={}", claimed.id());
            return;
        }
        execute(claimed, run, ownerInstance, lease);
    }

    private void execute(AgentRunStateService.ClaimedRun claimed, AgentRun run,
                         String ownerInstance, AutoCloseable conversationLease) {
        String runId = claimed.id();
        try {
            var selection = modelCatalogService.resolveWithFallback(claimed.userId(),
                    ModelCatalogService.PURPOSE_AGENT, ModelCatalogService.PURPOSE_CHAT);
            var chatModel = chatModelFactory.model(selection.config());
            boolean webSearch = true;
            var tools = toolFactory.createTools(claimed.tenantId(), claimed.userId(), SUPER_AGENT_KEY,
                    claimed.conversationId(), runId, webSearch);
            GraphCheckpoint checkpoint = run.checkpoint();
            String resumeFrom = checkpoint == null ? null : ManusGraphOrchestrator.resumeNode(checkpoint);
            GraphCheckpoint resumeCheckpoint = resumeFrom == null ? null
                    : ManusGraphOrchestrator.prepareResumeCheckpoint(checkpoint, resumeFrom);
            Runnable cleanup = () -> {
                projectExecutionLockService.release(runId);
                try { conversationLease.close(); } catch (Exception ignored) { }
            };
            ManusGraphOrchestrator.ManusRunRequest request = new ManusGraphOrchestrator.ManusRunRequest(
                    claimed.conversationId(), claimed.tenantId(), claimed.userId(), chatModel,
                    claimed.input(), java.util.List.of(), tools, claimed.knowledgeContext(), null,
                    null, runId, claimed.permissionMode(), resumeCheckpoint, resumeFrom, cleanup,
                    result -> completeRun(claimed, ownerInstance, result),
                    () -> stateService.shouldStopAtSafePoint(runId, ownerInstance, claimed.fencingToken()));
            // SSE 仅作观察通道：v2 不接管 emitter，客户端经 reconnect/snapshot+events 观察
            orchestrator.runStream(request, agentExecutor);
            log.info("run 已派发执行；runId={}, attempt={}, fencing={}", runId, claimed.resumeCount(), claimed.fencingToken());
        } catch (RuntimeException exception) {
            log.error("派发执行装配失败；runId={}", runId, exception);
            // 装配失败按一次失败尝试收口（不重试相同装配错误）
            completeRun(claimed, ownerInstance, new ManusGraphOrchestrator.ManusRunResult(
                    null, List.of(), false, "FAILED", exception.getMessage()));
        }
    }

    private void completeRun(AgentRunStateService.ClaimedRun claimed, String ownerInstance,
                             ManusGraphOrchestrator.ManusRunResult result) {
        String runId = claimed.id();
        try {
            if (result.completed()) {
                conversationService.completeUserTurn(claimed.tenantId(), claimed.userId(), claimed.userMessageId());
            } else {
                conversationService.markUserTurnInterrupted(claimed.tenantId(), claimed.userId(), claimed.userMessageId());
            }
            String answer = result.answer();
            Long messageId = conversationService.findAssistantReplyId(claimed.tenantId(), claimed.userId(),
                    SUPER_AGENT_KEY, claimed.conversationId(), claimed.userMessageId());
            if (messageId != null) {
                conversationService.updateAssistantReply(claimed.tenantId(), claimed.userId(), SUPER_AGENT_KEY,
                        claimed.conversationId(), messageId, answer, result.status(), null);
            } else {
                conversationService.appendAssistantReply(claimed.tenantId(), claimed.userId(), SUPER_AGENT_KEY,
                        claimed.conversationId(), claimed.userMessageId(), answer, result.status(), runId, null, null);
            }
            agentRunService.endAssignment(runId, result.status() + ":" + (result.error() == null ? "ok" : result.error()));
            stateService.complete(runId, ownerInstance, claimed.fencingToken(), result.status(), answer, result.error());
        } catch (RuntimeException persistenceError) {
            log.error("终态持久化失败；runId={}", runId, persistenceError);
        } finally {
            projectExecutionLockService.release(runId);
            runWorkspaceService.finish(runId, result.status());
        }
    }

    /** 心跳续约：延续本实例全部 engine_version=2 工作中 run 的租约。 */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void heartbeatLeases() {
        if (!enabled) return;
        try {
            stateService.heartbeat(instanceRegistry.currentInstanceId());
        } catch (Exception exception) {
            log.warn("派发心跳失败：{}", exception.getMessage());
        }
    }

    /** 租约回收：执行实例死亡后的有界重试与阻塞。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void reapExpiredLeases() {
        if (!enabled) return;
        try {
            stateService.reapExpiredLeases();
        } catch (Exception exception) {
            log.warn("派发租约回收失败：{}", exception.getMessage());
        }
    }
}
