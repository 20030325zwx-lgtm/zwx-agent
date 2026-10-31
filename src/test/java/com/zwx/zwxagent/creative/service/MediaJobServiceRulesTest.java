package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.MediaJobStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** A22/A24（C08）纯规则：前端可用动作与服务端状态一致；请求哈希对字段顺序稳定、对内容敏感。 */
class MediaJobServiceRulesTest {

    @Test
    void allowedActionsFollowStatus() {
        Assertions.assertEquals(java.util.List.of("cancel"),
                MediaJobService.allowedActions(MediaJobStatus.QUEUED));
        Assertions.assertEquals(java.util.List.of("cancel"),
                MediaJobService.allowedActions(MediaJobStatus.RUNNING));
        Assertions.assertEquals(java.util.List.of(),
                MediaJobService.allowedActions(MediaJobStatus.SUBMITTING));
        Assertions.assertEquals(java.util.List.of("resumeIngest"),
                MediaJobService.allowedActions(MediaJobStatus.INGEST_FAILED));
        Assertions.assertEquals(java.util.List.of("retry"),
                MediaJobService.allowedActions(MediaJobStatus.FAILED));
        Assertions.assertEquals(java.util.List.of("retryWithRisk"),
                MediaJobService.allowedActions(MediaJobStatus.RECONCILING));
        Assertions.assertTrue(MediaJobService.allowedActions(MediaJobStatus.SUCCEEDED).contains("download"));
        Assertions.assertTrue(MediaJobService.allowedActions(MediaJobStatus.CANCEL_REQUESTED).isEmpty());
    }

    @Test
    void requestHashIsOrderInsensitiveAndContentSensitive() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("size", "1024*1024");
        first.put("watermark", false);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("watermark", false);
        second.put("size", "1024*1024");
        String a = MediaJobService.requestHash("text_to_image", "想法", "最终", 1L, first, java.util.List.of());
        String b = MediaJobService.requestHash("text_to_image", "想法", "最终", 1L, second, java.util.List.of());
        Assertions.assertEquals(a, b, "参数顺序不同不能产生不同哈希");

        String changed = MediaJobService.requestHash("text_to_image", "想法", "改过的最终", 1L, first, java.util.List.of());
        Assertions.assertNotEquals(a, changed, "内容变化必须产生不同哈希（同键改内容应 409）");
    }
}
