package com.zwx.zwxagent.creative.domain;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MediaJobStatusTest {

    @Test
    void followsStateMachineEdges() {
        Assertions.assertTrue(MediaJobStatus.QUEUED.canTransitionTo(MediaJobStatus.SUBMITTING));
        Assertions.assertTrue(MediaJobStatus.SUBMITTING.canTransitionTo(MediaJobStatus.RECONCILING));
        Assertions.assertTrue(MediaJobStatus.SUBMITTING.canTransitionTo(MediaJobStatus.FETCHING));
        Assertions.assertTrue(MediaJobStatus.RUNNING.canTransitionTo(MediaJobStatus.CANCEL_REQUESTED));
        Assertions.assertTrue(MediaJobStatus.CANCEL_REQUESTED.canTransitionTo(MediaJobStatus.FETCHING));
        Assertions.assertTrue(MediaJobStatus.FETCHING.canTransitionTo(MediaJobStatus.SUCCEEDED));
        Assertions.assertTrue(MediaJobStatus.FETCHING.canTransitionTo(MediaJobStatus.INGEST_FAILED));
        Assertions.assertTrue(MediaJobStatus.INGEST_FAILED.canTransitionTo(MediaJobStatus.FETCHING));
    }

    @Test
    void rejectsIllegalEdges() {
        // 不允许跳过提交/查询阶段，也不允许终态复活
        Assertions.assertFalse(MediaJobStatus.QUEUED.canTransitionTo(MediaJobStatus.RUNNING));
        Assertions.assertFalse(MediaJobStatus.QUEUED.canTransitionTo(MediaJobStatus.FAILED));
        Assertions.assertFalse(MediaJobStatus.SUBMITTING.canTransitionTo(MediaJobStatus.SUCCEEDED));
        Assertions.assertFalse(MediaJobStatus.RUNNING.canTransitionTo(MediaJobStatus.QUEUED));
        Assertions.assertFalse(MediaJobStatus.FAILED.canTransitionTo(MediaJobStatus.RUNNING));
        Assertions.assertFalse(MediaJobStatus.SUCCEEDED.canTransitionTo(MediaJobStatus.FETCHING));
        Assertions.assertThrows(IllegalStateException.class,
                () -> MediaJobStatus.QUEUED.assertCanTransitionTo(MediaJobStatus.FAILED));
    }

    @Test
    void terminalStatesCannotLeave() {
        for (MediaJobStatus status : MediaJobStatus.values()) {
            if (status.terminal()) {
                Assertions.assertFalse(status.canTransitionTo(status));
                Assertions.assertFalse(status.canTransitionTo(MediaJobStatus.RUNNING));
            }
        }
        Assertions.assertTrue(MediaJobStatus.SUCCEEDED.terminal());
        Assertions.assertTrue(MediaJobStatus.FAILED.terminal());
        Assertions.assertTrue(MediaJobStatus.CANCELLED.terminal());
    }

    @Test
    void activeSetMatchesSchedulerScope() {
        Assertions.assertFalse(MediaJobStatus.active().contains(MediaJobStatus.SUCCEEDED));
        Assertions.assertTrue(MediaJobStatus.active().contains(MediaJobStatus.RECONCILING));
        Assertions.assertTrue(MediaJobStatus.active().contains(MediaJobStatus.INGEST_FAILED));
    }
}
