package com.zwx.zwxagent.agent.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 阶段推导：status + 等待原因 + 后台任务数 → 规范阶段（面板/任务列表按此分组）。 */
class RunPhaseTest {

    @Test
    void completedWithoutBackgroundIsDone() {
        assertThat(RunPhase.derive("COMPLETED", null, 0)).isEqualTo(RunPhase.DONE);
    }

    @Test
    void completedWithBackgroundStaysWorking() {
        assertThat(RunPhase.derive("COMPLETED", null, 2)).isEqualTo(RunPhase.WORKING);
    }

    @Test
    void interruptedWithWaitingReasonWaitsForHuman() {
        assertThat(RunPhase.derive("INTERRUPTED", "CONFIRM_PLAN", 0)).isEqualTo(RunPhase.WAITING_HUMAN);
        assertThat(RunPhase.derive("INTERRUPTED", "CONFIRM_TOOL", 0)).isEqualTo(RunPhase.WAITING_HUMAN);
        assertThat(RunPhase.derive("INTERRUPTED", "NEED_INPUT", 0)).isEqualTo(RunPhase.WAITING_HUMAN);
    }

    @Test
    void interruptedWithoutWaitingReasonIsStopped() {
        assertThat(RunPhase.derive("INTERRUPTED", null, 0)).isEqualTo(RunPhase.STOPPED);
        assertThat(RunPhase.derive("INTERRUPTED", "  ", 0)).isEqualTo(RunPhase.STOPPED);
    }

    @Test
    void failedRunningAndUnknownAreClassified() {
        assertThat(RunPhase.derive("FAILED", null, 0)).isEqualTo(RunPhase.FAILED);
        assertThat(RunPhase.derive("RUNNING", null, 0)).isEqualTo(RunPhase.WORKING);
        assertThat(RunPhase.derive(null, null, 0)).isEqualTo(RunPhase.QUEUED);
        assertThat(RunPhase.derive("completed ", null, 0)).isEqualTo(RunPhase.DONE);
    }

    @Test
    void waitingReasonParsingIsLenient() {
        assertThat(WaitingReason.from("need_input")).isEqualTo(WaitingReason.NEED_INPUT);
        assertThat(WaitingReason.from("CONFIRM_TOOL")).isEqualTo(WaitingReason.CONFIRM_TOOL);
        assertThat(WaitingReason.from("nonsense")).isNull();
        assertThat(WaitingReason.NEED_INPUT.label()).contains("补充信息");
    }
}
