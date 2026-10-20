package com.zwx.zwxagent.conversation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** PostgreSQL-backed lease lock; acquisition and release are safe across request threads and app instances. */
@Component
public class ConversationLockManager {

    private static final ScheduledExecutorService RENEWER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "conversation-lock-renewer");
        thread.setDaemon(true);
        return thread;
    });

    private final JdbcTemplate jdbcTemplate;
    private final Duration leaseDuration;
    private final ApplicationInstanceRegistry instanceRegistry;

    public ConversationLockManager(JdbcTemplate jdbcTemplate,
                                   @Value("${app.agent.conversation-lock-lease-seconds:600}") long leaseSeconds) {
        this(jdbcTemplate, leaseSeconds, null);
    }

    @Autowired
    public ConversationLockManager(JdbcTemplate jdbcTemplate,
                                   @Value("${app.agent.conversation-lock-lease-seconds:600}") long leaseSeconds,
                                   ApplicationInstanceRegistry instanceRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.leaseDuration = Duration.ofSeconds(Math.max(60, leaseSeconds));
        this.instanceRegistry = instanceRegistry;
    }

    public Lease tryAcquire(String conversationId) {
        String token = UUID.randomUUID().toString();
        String ownerInstance = instanceRegistry == null ? null : instanceRegistry.currentInstanceId();
        try {
            String acquired = jdbcTemplate.queryForObject("""
                    INSERT INTO agent_conversation_lock (conversation_id, owner_token, owner_instance, expires_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 second'))
                    ON CONFLICT (conversation_id) DO UPDATE
                    SET owner_token = EXCLUDED.owner_token,
                        owner_instance = EXCLUDED.owner_instance,
                        expires_at = EXCLUDED.expires_at
                    WHERE agent_conversation_lock.expires_at < CURRENT_TIMESTAMP
                       OR (EXCLUDED.owner_instance IS NOT NULL AND NOT EXISTS (
                           SELECT 1 FROM agent_instance i
                           WHERE i.instance_id = agent_conversation_lock.owner_instance
                             AND i.heartbeat_at > CURRENT_TIMESTAMP - INTERVAL '15 seconds'))
                    RETURNING owner_token
                    """, String.class, conversationId, token, ownerInstance, leaseDuration.toSeconds());
            return token.equals(acquired) ? new Lease(conversationId, token) : null;
        } catch (EmptyResultDataAccessException exception) {
            return null;
        }
    }

    private void release(String conversationId, String token) {
        jdbcTemplate.update("DELETE FROM agent_conversation_lock WHERE conversation_id = ? AND owner_token = ?",
                conversationId, token);
    }

    public final class Lease implements AutoCloseable {
        private final String conversationId;
        private final String token;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ScheduledFuture<?> renewal;

        private Lease(String conversationId, String token) {
            this.conversationId = conversationId;
            this.token = token;
            long renewalSeconds = Math.max(20, leaseDuration.toSeconds() / 3);
            this.renewal = RENEWER.scheduleAtFixedRate(this::renew, renewalSeconds, renewalSeconds, TimeUnit.SECONDS);
        }

        private void renew() {
            if (closed.get()) return;
            try {
                int updated = jdbcTemplate.update("""
                        UPDATE agent_conversation_lock
                        SET expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
                        WHERE conversation_id = ? AND owner_token = ?
                        """, leaseDuration.toSeconds(), conversationId, token);
                if (updated == 0) close();
            } catch (RuntimeException exception) {
                // Keep ownership intact; the next scheduled renewal retries without releasing another run's lock.
                org.slf4j.LoggerFactory.getLogger(ConversationLockManager.class)
                        .warn("Conversation lease renewal failed; conversationId={}", conversationId);
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            renewal.cancel(false);
            release(conversationId, token);
        }
    }
}
