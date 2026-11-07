package com.zwx.zwxagent.agent.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时任务调度器：每分钟扫描一次到期任务并拉起智能体运行。
 * 认领在仓库层用 FOR UPDATE SKIP LOCKED 完成，本地与服务器双实例并发安全；
 * 总开关 app.schedules.enabled（默认 true）。
 */
@Component
public class AgentScheduleRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentScheduleRunner.class);

    private final AgentScheduleService service;

    public AgentScheduleRunner(AgentScheduleService service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void tick() {
        if (!service.schedulerEnabled()) return;
        try {
            service.fireDue();
        } catch (Exception exception) {
            // 调度永不因单批失败而停止；失败明细已在 dispatch 内落 fire 记录
            log.warn("定时任务调度批次失败：{}", exception.getMessage());
        }
    }
}
