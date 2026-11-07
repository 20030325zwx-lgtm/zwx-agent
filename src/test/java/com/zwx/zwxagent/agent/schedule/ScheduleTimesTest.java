package com.zwx.zwxagent.agent.schedule;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/** 定时任务的下一次运行计算：每天/每周/仅一次、顺延不重复、过期拒绝。 */
class ScheduleTimesTest {

    private static Instant at(String iso) {
        return ZonedDateTime.parse(iso).toInstant();
    }

    @Test
    void dailyRollsToTomorrowWhenTodaySlotPassed() {
        var schedule = ScheduleTimes.Schedule.daily(LocalTime.of(9, 0));
        Instant after = at("2026-11-07T10:00:00+08:00");
        Assertions.assertEquals(at("2026-11-08T09:00:00+08:00"), ScheduleTimes.nextRun(schedule, after));
        // 当日时刻未到：今天就是下一个点
        Assertions.assertEquals(at("2026-11-07T09:00:00+08:00"), ScheduleTimes.nextRun(schedule, at("2026-11-07T08:00:00+08:00")));
    }

    @Test
    void weeklyPicksNextOccurrenceAndNeverSameMoment() {
        // 2026-11-07 是周六（7）
        var schedule = ScheduleTimes.Schedule.weekly(1, LocalTime.of(9, 0));
        Instant saturdayAfterSlot = at("2026-11-07T10:00:00+08:00");
        Assertions.assertEquals(at("2026-11-09T09:00:00+08:00"), ScheduleTimes.nextRun(schedule, saturdayAfterSlot));
        // 触发时刻本身不算“下一个点”：周一 09:00 触发后应顺延到下周一
        Instant mondayAtSlot = at("2026-11-09T09:00:00+08:00");
        Assertions.assertEquals(at("2026-11-16T09:00:00+08:00"), ScheduleTimes.nextRun(schedule, mondayAtSlot));
    }

    @Test
    void onceReturnsItselfOrNothing() {
        var future = ScheduleTimes.Schedule.once(at("2026-11-08T08:00:00+08:00"));
        Assertions.assertEquals(at("2026-11-08T08:00:00+08:00"), ScheduleTimes.nextRun(future, at("2026-11-07T20:00:00+08:00")));
        Assertions.assertNull(ScheduleTimes.nextRun(future, at("2026-11-08T09:00:00+08:00")));
    }

    @Test
    void initialNextRunRejectsPastAndIncomplete() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScheduleTimes.initialNextRun(ScheduleTimes.Schedule.once(at("2020-01-01T00:00:00+08:00")), Instant.now()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> ScheduleTimes.initialNextRun(new ScheduleTimes.Schedule("DAILY", null, null, null), Instant.now()));
        Assertions.assertNotNull(ScheduleTimes.initialNextRun(
                ScheduleTimes.Schedule.daily(LocalTime.of(23, 0)), Instant.now()));
    }

    @Test
    void describeUsesChineseWeekday() {
        Assertions.assertEquals("每周一 09:00", ScheduleTimes.describe(ScheduleTimes.Schedule.weekly(1, LocalTime.of(9, 0))));
        Assertions.assertEquals("每天 08:30", ScheduleTimes.describe(ScheduleTimes.Schedule.daily(LocalTime.of(8, 30))));
    }
}
