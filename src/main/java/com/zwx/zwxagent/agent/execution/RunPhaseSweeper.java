package com.zwx.zwxagent.agent.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 后台任务扫尾：run 结束时媒体任务仍在跑 → phase 保持 WORKING 并保留 30 分钟 slot；
 * 任务清零 → DONE；超时 → BLOCKED（等待人工处理）。
 */
@Component
public class RunPhaseSweeper {

    private static final Logger log = LoggerFactory.getLogger(RunPhaseSweeper.class);

    private final AgentRunService agentRunService;

    public RunPhaseSweeper(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 45_000)
    public void sweep() {
        try {
            int updated = agentRunService.sweepBackgroundRuns();
            if (updated > 0) log.debug("Swept {} run(s) with background jobs", updated);
        } catch (Exception exception) {
            log.warn("Run background sweep failed: {}", exception.getMessage());
        }
    }
}
