package com.zwx.zwxagent.agent.graph;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

class RunStreamTest {

    @Test
    void replaysOnlyEventsAfterLastEventId() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RunStream stream = new RunStream("run-1", 100, 30, scheduler, () -> { }, () -> { });
            stream.send("activity", "first");
            stream.send("message", "second");
            SseEmitter emitter = Mockito.mock(SseEmitter.class);

            Assertions.assertTrue(stream.attach(emitter, 1));
            Mockito.verify(emitter, Mockito.times(1)).send(Mockito.any(SseEmitter.SseEventBuilder.class));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void rejectsReplayWhenHistoryNoLongerCoversLastEventId() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RunStream stream = new RunStream("run-2", 100, 30, scheduler, () -> { }, () -> { });
            for (int i = 0; i < 150; i++) stream.send("activity", "event-" + i);
            SseEmitter emitter = Mockito.mock(SseEmitter.class);
            Assertions.assertFalse(stream.attach(emitter, 1));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void completionAppendsDoneAndMarksStreamCompleted() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            RunStream stream = new RunStream("run-3", 100, 30, scheduler, () -> { }, () -> { });
            stream.complete();
            Assertions.assertTrue(stream.completed());
            Assertions.assertTrue(stream.history().stream()
                    .anyMatch(event -> "message".equals(event.type()) && "[DONE]".equals(event.data())));
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void cancelRunsStopTaskImmediatelyAndOnlyOnceAfterCompletion() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            java.util.concurrent.atomic.AtomicInteger stops = new java.util.concurrent.atomic.AtomicInteger();
            RunStream stream = new RunStream("run-4", 100, 30, scheduler, stops::incrementAndGet, () -> { });

            Assertions.assertTrue(stream.cancel());
            Assertions.assertEquals(1, stops.get());

            stream.complete();
            Assertions.assertFalse(stream.cancel());
            Assertions.assertEquals(1, stops.get());
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void parsesLastEventIdWithRunPrefix() {
        Assertions.assertEquals(42, RunStream.parseSequence("run-1:42"));
        Assertions.assertEquals(42, RunStream.parseSequence("42"));
        Assertions.assertEquals(0, RunStream.parseSequence(null));
        Assertions.assertEquals(0, RunStream.parseSequence("not-a-number"));
    }
}
