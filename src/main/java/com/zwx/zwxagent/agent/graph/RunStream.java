package com.zwx.zwxagent.agent.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 一次 run 的 SSE 连接与事件历史。
 * 执行线程只向 RunStream 写事件；RunStream 负责当前 emitter、Last-Event-ID 重放，
 * 以及客户端断开后的宽限期停止。事件历史保存在当前 JVM，进程重启后的续跑由 agent_run checkpoint 负责。
 */
@Slf4j
public final class RunStream {

    public record Event(long sequence, String type, String data) {
    }

    private final String runId;
    private final int maxHistory;
    private final long graceSeconds;
    private final ScheduledExecutorService scheduler;
    private final Runnable stopTask;
    private final Runnable cleanup;
    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<Event> history = new CopyOnWriteArrayList<>();
    private final AtomicReference<SseEmitter> emitter = new AtomicReference<>();
    private final AtomicBoolean completed = new AtomicBoolean();
    private final AtomicBoolean cleaned = new AtomicBoolean();
    private volatile ScheduledFuture<?> stopFuture;

    public RunStream(String runId, int maxHistory, long graceSeconds,
                     ScheduledExecutorService scheduler, Runnable stopTask, Runnable cleanup) {
        this.runId = runId;
        this.maxHistory = Math.max(100, maxHistory);
        this.graceSeconds = Math.max(1, graceSeconds);
        this.scheduler = scheduler;
        this.stopTask = stopTask == null ? () -> { } : stopTask;
        this.cleanup = cleanup == null ? () -> { } : cleanup;
    }

    public String runId() {
        return runId;
    }

    public boolean completed() {
        return completed.get();
    }

    public long lastSequence() {
        return sequence.get();
    }

    public SseEmitter createEmitter() {
        SseEmitter created = new SseEmitter(TimeUnit.MINUTES.toMillis(6));
        cancelStop();
        emitter.set(created);
        return created;
    }

    /**
     * 绑定新的 SSE 连接并重放 lastSequence 之后的事件。
     *
     * @return false 表示请求的 lastSequence 早于仍保留的历史，无法无缺口重放
     */
    public synchronized boolean attach(SseEmitter target, long lastSequence) {
        long first = history.isEmpty() ? sequence.get() + 1 : history.get(0).sequence();
        if (lastSequence > 0 && lastSequence + 1 < first) {
            return false;
        }
        cancelStop();
        emitter.set(target);
        for (Event event : history) {
            if (event.sequence() <= lastSequence) continue;
            if (!deliver(target, event)) return false;
        }
        if (completed.get()) {
            emitter.compareAndSet(target, null);
            try {
                target.complete();
            } catch (Exception ignored) {
                // already gone
            }
        }
        return true;
    }

    public void detach(SseEmitter target) {
        if (emitter.compareAndSet(target, null)) {
            scheduleStop();
        }
    }

    public void send(String type, String data) {
        if (completed.get()) return;
        Event event;
        synchronized (this) {
            event = new Event(sequence.incrementAndGet(), type, data == null ? "" : data);
            history.add(event);
            trimHistory();
        }
        SseEmitter current = emitter.get();
        if (current != null && !deliver(current, event)) {
            detach(current);
        }
    }

    public void sendMessage(String chunk) {
        send("message", chunk);
    }

    public void complete() {
        if (!completed.compareAndSet(false, true)) return;
        sendEventWithoutCompletingCheck("message", "[DONE]");
        SseEmitter current = emitter.getAndSet(null);
        cancelStop();
        if (current != null) {
            try {
                current.complete();
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    private void sendEventWithoutCompletingCheck(String type, String data) {
        Event event;
        synchronized (this) {
            event = new Event(sequence.incrementAndGet(), type, data == null ? "" : data);
            history.add(event);
            trimHistory();
        }
        SseEmitter current = emitter.get();
        if (current != null && !deliver(current, event)) {
            emitter.compareAndSet(current, null);
        }
    }

    public void cleanup() {
        if (cleaned.compareAndSet(false, true)) {
            try {
                cleanup.run();
            } catch (Exception exception) {
                log.warn("run stream cleanup failed; runId={}", runId, exception);
            }
        }
    }

    private boolean deliver(SseEmitter target, Event event) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .id(runId + ":" + event.sequence())
                    .data(event.data());
            if (!"message".equals(event.type())) {
                builder.name(event.type());
            }
            target.send(builder);
            return true;
        } catch (Exception exception) {
            log.debug("SSE delivery failed; runId={}, eventType={}", runId, event.type());
            return false;
        }
    }

    private void trimHistory() {
        int excess = history.size() - maxHistory;
        for (int i = 0; i < excess; i++) {
            history.remove(0);
        }
    }

    private synchronized void scheduleStop() {
        if (completed.get() || stopFuture != null && !stopFuture.isDone()) return;
        stopFuture = scheduler.schedule(() -> {
            synchronized (this) {
                if (completed.get() || emitter.get() != null) return;
                try {
                    stopTask.run();
                } catch (Exception exception) {
                    log.warn("run stream grace stop failed; runId={}", runId, exception);
                }
            }
        }, graceSeconds, TimeUnit.SECONDS);
    }

    private synchronized void cancelStop() {
        if (stopFuture != null) {
            stopFuture.cancel(false);
            stopFuture = null;
        }
    }

    public static long parseSequence(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return 0;
        String value = lastEventId;
        int colon = value.lastIndexOf(':');
        if (colon >= 0) value = value.substring(colon + 1);
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    public List<Event> history() {
        return List.copyOf(history);
    }
}
