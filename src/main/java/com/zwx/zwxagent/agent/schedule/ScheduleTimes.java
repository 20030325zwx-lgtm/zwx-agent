package com.zwx.zwxagent.agent.schedule;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 定时任务的下一次运行时间计算（纯函数，便于单测）。
 * 时刻一律按固定时区 Asia/Shanghai 解释——本地与服务器两个调度实例可能处于不同时区，
 * 计算必须与机器时区无关；补跑策略（错过只补一次）由调度器保证，这里只给“下一个点”。
 */
public final class ScheduleTimes {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 迟到超过该窗口不再补跑（防止长时间停机后瞬间连发）。 */
    public static final Duration MAX_CATCH_UP = Duration.ofHours(24);

    private ScheduleTimes() {
    }

    public record Schedule(String kind, LocalTime runTime, Integer weeklyDow, Instant onceAt) {

        public static Schedule daily(LocalTime runTime) {
            return new Schedule("DAILY", runTime, null, null);
        }

        public static Schedule weekly(int isoDow, LocalTime runTime) {
            return new Schedule("WEEKLY", runTime, isoDow, null);
        }

        public static Schedule once(Instant onceAt) {
            return new Schedule("ONCE", null, null, onceAt);
        }
    }

    /**
     * `after`（通常为 now，或刚触发的时刻）之后的下一个运行点；没有下一个点（ONCE 已过期）返回 null。
     * 每次触发后以触发时刻为基准顺延，保证同一周期不会二次触发。
     */
    public static Instant nextRun(Schedule schedule, Instant after) {
        if (schedule == null || after == null) return null;
        ZonedDateTime base = after.atZone(ZONE);
        return switch (schedule.kind()) {
            case "DAILY" -> {
                ZonedDateTime candidate = base.with(schedule.runTime());
                if (!candidate.isAfter(base)) candidate = candidate.plusDays(1);
                yield candidate.toInstant();
            }
            case "WEEKLY" -> {
                ZonedDateTime candidate = base.with(schedule.runTime());
                int today = candidate.getDayOfWeek().getValue();
                int delta = (schedule.weeklyDow() - today + 7) % 7;
                candidate = candidate.plusDays(delta);
                if (!candidate.isAfter(base)) candidate = candidate.plusWeeks(1);
                yield candidate.toInstant();
            }
            case "ONCE" -> schedule.onceAt() != null && schedule.onceAt().isAfter(after) ? schedule.onceAt() : null;
            default -> null;
        };
    }

    /** 创建/启用时的入口校验：ONCE 必须在未来（含迟到宽限窗口），其余参数完备性。 */
    public static Instant initialNextRun(Schedule schedule, Instant now) {
        if (schedule == null) throw new IllegalArgumentException("缺少调度配置");
        if ("DAILY".equals(schedule.kind())) {
            if (schedule.runTime() == null) throw new IllegalArgumentException("每天调度需要运行时刻");
        } else if ("WEEKLY".equals(schedule.kind())) {
            if (schedule.runTime() == null || schedule.weeklyDow() == null
                    || schedule.weeklyDow() < 1 || schedule.weeklyDow() > 7) {
                throw new IllegalArgumentException("每周调度需要星期（1-7）与运行时刻");
            }
        } else if ("ONCE".equals(schedule.kind())) {
            if (schedule.onceAt() == null) throw new IllegalArgumentException("仅一次调度需要运行时间");
        } else {
            throw new IllegalArgumentException("不支持的调度类型：" + schedule.kind());
        }
        Instant next = nextRun(schedule, now);
        if (next == null) throw new IllegalArgumentException("运行时间已过期，请选择未来的时间");
        return next;
    }

    /** 错过的点是否还值得补跑：停机超过宽限窗口则直接顺延到下一个周期。 */
    public static boolean shouldCatchUp(Instant dueRunAt, Instant now) {
        return dueRunAt != null && Duration.between(dueRunAt, now).compareTo(MAX_CATCH_UP) <= 0;
    }

    /** 展示用：节奏摘要（前端也可自行拼，这里保证后端口径一致）。 */
    public static String describe(Schedule schedule) {
        return switch (schedule.kind()) {
            case "DAILY" -> "每天 " + schedule.runTime();
            case "WEEKLY" -> "每周" + "一二三四五六日".charAt(schedule.weeklyDow() - 1) + " " + schedule.runTime();
            case "ONCE" -> "仅一次 " + (schedule.onceAt() == null ? "" : schedule.onceAt().atZone(ZONE).toLocalDateTime());
            default -> schedule.kind();
        };
    }

    /** 防御：ISO 星期几合法范围（1=周一 … 7=周日）。 */
    public static boolean isValidIsoDow(Integer dow) {
        return dow != null && dow >= 1 && dow <= 7;
    }
}
