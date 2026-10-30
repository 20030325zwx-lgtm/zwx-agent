package com.zwx.zwxagent.creative.domain;

import java.util.Map;
import java.util.Set;

/**
 * 媒体任务状态机（总体设计 §8.1）。
 * 迁移只允许沿合法边进行；非法迁移必须抛错，不允许静默改状态。
 */
public enum MediaJobStatus {
    QUEUED,
    SUBMITTING,
    RECONCILING,
    RUNNING,
    CANCEL_REQUESTED,
    FETCHING,
    INGEST_FAILED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Map<MediaJobStatus, Set<MediaJobStatus>> NEXT = Map.of(
            QUEUED, Set.of(SUBMITTING, CANCELLED),
            SUBMITTING, Set.of(QUEUED, RUNNING, FETCHING, FAILED, RECONCILING),
            RECONCILING, Set.of(RUNNING, FETCHING, FAILED),
            RUNNING, Set.of(FETCHING, FAILED, CANCEL_REQUESTED),
            CANCEL_REQUESTED, Set.of(CANCELLED, RUNNING, FETCHING, FAILED),
            FETCHING, Set.of(SUCCEEDED, INGEST_FAILED),
            INGEST_FAILED, Set.of(FETCHING),
            SUCCEEDED, Set.of(),
            FAILED, Set.of(),
            CANCELLED, Set.of());

    public boolean canTransitionTo(MediaJobStatus target) {
        return NEXT.getOrDefault(this, Set.of()).contains(target);
    }

    public void assertCanTransitionTo(MediaJobStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("非法任务状态迁移：" + this + " -> " + target);
        }
    }

    public boolean terminal() {
        return NEXT.get(this).isEmpty();
    }

    /** 参与调度与在途统计的未终态集合。 */
    public static Set<MediaJobStatus> active() {
        return Set.of(QUEUED, SUBMITTING, RECONCILING, RUNNING, CANCEL_REQUESTED, FETCHING, INGEST_FAILED);
    }
}
