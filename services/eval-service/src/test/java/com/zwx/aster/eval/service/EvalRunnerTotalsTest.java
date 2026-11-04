package com.zwx.aster.eval.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 汇总口径：total/passed/failed/skipped/passRate/avgLatencyMs/judgeAvg。 */
class EvalRunnerTotalsTest {

    @Test
    void computesTotalsWithSkipAndJudge() {
        Map<String, Object> skippedRow = new java.util.LinkedHashMap<>();
        skippedRow.put("passed", null);
        skippedRow.put("latency_ms", 0L);
        var results = List.of(
                Map.<String, Object>of("passed", true, "latency_ms", 100L,
                        "judge", com.zwx.aster.eval.domain.EvalJson.toJson(Map.of("average", 4.0))),
                Map.<String, Object>of("passed", false, "latency_ms", 300L),
                skippedRow);
        var totals = EvalRunner.totals(results);
        assertEquals(3, totals.get("total"));
        assertEquals(1, totals.get("passed"));
        assertEquals(1, totals.get("failed"));
        assertEquals(1, totals.get("skipped"));
        assertEquals(0.5, totals.get("passRate"));
        assertEquals(200L, totals.get("avgLatencyMs"));
        assertEquals(4.0, totals.get("judgeAvg"));
    }

    @Test
    void backflowCaseRules() {
        var up = EvalDatasetService.buildBackflowCase("abcd1234-5678", "up", "好", "年假？", "5 天。");
        assertEquals("5 天。", up.get("reference"));
        assertEquals("badcase", up.get("source"));
        var down = EvalDatasetService.buildBackflowCase("abcd1234-5678", "down", "缺拒答", "天气？", "晴。");
        assertEquals("", down.get("reference"));
        assertTrue(String.valueOf(down.get("notes")).contains("[原回答] 晴。"));
    }
}
