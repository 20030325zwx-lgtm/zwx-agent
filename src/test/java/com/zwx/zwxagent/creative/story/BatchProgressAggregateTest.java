package com.zwx.zwxagent.creative.story;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * 批次进度聚合（P3，纯函数）：分状态计数；完成率分母排除 SKIPPED（明确排除项不冒充进度）。
 */
class BatchProgressAggregateTest {

    private static BatchProductionService.ItemView item(String status) {
        return new BatchProductionService.ItemView(
                new com.zwx.zwxagent.creative.domain.VideoBatchItemRecord(
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "t", "u",
                        java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), status,
                        null, null, 0, null, null, null),
                "镜头 01", 4);
    }

    @Test
    void countsEachStatusSeparately() {
        var progress = BatchProductionService.aggregate(List.of(
                item("PENDING_SUBMIT"), item("SUBMITTED"), item("DONE"), item("FAILED"), item("SKIPPED")));
        Assertions.assertEquals(5, progress.total());
        Assertions.assertEquals(1, progress.pending());
        Assertions.assertEquals(1, progress.inflight());
        Assertions.assertEquals(1, progress.done());
        Assertions.assertEquals(1, progress.failed());
        Assertions.assertEquals(1, progress.skipped());
    }

    @Test
    void donePercentExcludesSkippedFromDenominator() {
        // 4 个可执行项中 2 个成功 → 50%；SKIPPED 不进分母
        var progress = BatchProductionService.aggregate(List.of(
                item("DONE"), item("DONE"), item("PENDING_SUBMIT"), item("SUBMITTED"),
                item("SKIPPED"), item("SKIPPED")));
        Assertions.assertEquals(6, progress.total()); // 总数含已停止项（展示口径）
        Assertions.assertEquals(50, progress.donePercent()); // 完成率分母排除 SKIPPED
    }

    @Test
    void emptyBatchIsZeroPercentNotException() {
        var progress = BatchProductionService.aggregate(List.of());
        Assertions.assertEquals(0, progress.total());
        Assertions.assertEquals(0, progress.donePercent());
    }

    @Test
    void allSkippedMeansZeroPercent() {
        var progress = BatchProductionService.aggregate(List.of(item("SKIPPED"), item("SKIPPED")));
        Assertions.assertEquals(0, progress.donePercent());
    }
}
