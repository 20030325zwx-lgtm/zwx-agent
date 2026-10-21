package com.zwx.zwxagent.chat.conversation;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 每个 JVM 一个实例 ID，并周期性写入心跳。
 * 租约和 agent_run 记录 owner_instance，崩溃实例留下的租约可被其他实例识别并接管。
 */
@Slf4j
@Component
public class ApplicationInstanceRegistry {

    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "agent-instance-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final JdbcTemplate jdbcTemplate;
    private final String instanceId = UUID.randomUUID().toString();
    private ScheduledFuture<?> heartbeat;

    public ApplicationInstanceRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String currentInstanceId() {
        return instanceId;
    }

    @PostConstruct
    void register() {
        try {
            jdbcTemplate.update("INSERT INTO agent_instance (instance_id) VALUES (?)", instanceId);
            heartbeat = HEARTBEAT.scheduleAtFixedRate(this::touch, 5, 5, TimeUnit.SECONDS);
            log.info("agent instance registered; instanceId={}", instanceId);
        } catch (Exception exception) {
            log.warn("agent instance registry unavailable; crash recovery falls back to lease expiry", exception);
        }
    }

    private void touch() {
        try {
            jdbcTemplate.update("UPDATE agent_instance SET heartbeat_at = CURRENT_TIMESTAMP WHERE instance_id = ?", instanceId);
        } catch (Exception exception) {
            log.warn("agent instance heartbeat failed; instanceId={}", instanceId);
        }
    }

    @PreDestroy
    void unregister() {
        if (heartbeat != null) heartbeat.cancel(false);
        try {
            jdbcTemplate.update("DELETE FROM agent_instance WHERE instance_id = ?", instanceId);
        } catch (Exception exception) {
            log.warn("failed to remove agent instance record; instanceId={}", instanceId);
        }
    }
}
