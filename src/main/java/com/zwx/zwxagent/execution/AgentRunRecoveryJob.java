package com.zwx.zwxagent.execution;

import com.zwx.zwxagent.conversation.AgentConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 服务重启后回收没有活跃会话租约的 RUNNING 任务：
 * 保留最后检查点，把用户轮次和助手消息标成 INTERRUPTED，供前端“继续生成”。
 */
@Slf4j
@Component
public class AgentRunRecoveryJob {

    private static final String INTERRUPTED_ANSWER = "上一次回答在服务重启前未完成，已保留执行进度。可以点击继续生成。";

    private final AgentRunService agentRunService;
    private final AgentConversationService conversationService;
    private final java.util.concurrent.atomic.AtomicBoolean recovering = new java.util.concurrent.atomic.AtomicBoolean();

    public AgentRunRecoveryJob(AgentRunService agentRunService, AgentConversationService conversationService) {
        this.agentRunService = agentRunService;
        this.conversationService = conversationService;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Scheduled(fixedDelay = 15_000)
    public void recoverOrphanedRuns() {
        if (!recovering.compareAndSet(false, true)) return;
        try {
            recover();
        } finally {
            recovering.set(false);
        }
    }

    private void recover() {
        java.util.List<AgentRun> orphans;
        try {
            orphans = agentRunService.findOrphanedRunning();
        } catch (Exception exception) {
            log.warn("skip agent run recovery because the run store is unavailable", exception);
            return;
        }
        for (AgentRun run : orphans) {
            try {
                String answer = run.displayAnswer();
                if (answer == null || answer.isBlank()) answer = INTERRUPTED_ANSWER;
                Long assistantMessageId = run.assistantMessageId();
                if (assistantMessageId == null) {
                    assistantMessageId = conversationService.findAssistantReplyId(
                            run.tenantId(), run.userId(), run.agentKey(), run.conversationId(), run.userMessageId());
                }
                if (assistantMessageId == null) {
                    assistantMessageId = conversationService.appendAssistantReply(
                            run.tenantId(), run.userId(), run.agentKey(), run.conversationId(), run.userMessageId(),
                            answer, "INTERRUPTED", run.id(), run.ragTraceJson(), null);
                } else {
                    conversationService.updateAssistantReply(
                            run.tenantId(), run.userId(), run.agentKey(), run.conversationId(),
                            assistantMessageId, answer, "INTERRUPTED");
                }
                conversationService.markUserTurnInterrupted(run.tenantId(), run.userId(), run.userMessageId());
                agentRunService.bindAssistantMessage(run.id(), assistantMessageId);
                agentRunService.markInterrupted(run.id(), "服务重启时执行未完成，已保留检查点");
                log.info("recovered orphaned agent run; runId={}, conversationId={}", run.id(), run.conversationId());
            } catch (Exception exception) {
                log.warn("failed to recover orphaned agent run; runId={}", run.id(), exception);
            }
        }
    }
}
