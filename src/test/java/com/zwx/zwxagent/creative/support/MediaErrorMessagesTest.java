package com.zwx.zwxagent.creative.support;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 供应商失败可读化（P1 收尾）：常见失败面各自给出可读原因与下一步，
 * 未命中的原文保留摘要，不吞掉诊断信息。
 */
class MediaErrorMessagesTest {

    @Test
    void commonFailuresGetActionableMessages() {
        Assertions.assertEquals("模型不支持该视频时长",
                MediaErrorMessages.message("provider rejected: invalid duration 4s"));
        Assertions.assertEquals("模型不支持该画面尺寸",
                MediaErrorMessages.message("unsupported size 1024x1792 for this model"));
        Assertions.assertEquals("内容未通过供应商安全审核",
                MediaErrorMessages.message("content moderation flagged"));
        Assertions.assertEquals("供应商余额或配额不足",
                MediaErrorMessages.message("{\"code\":\"Arrearage\",\"message\":\"account balance\"}"));
        Assertions.assertEquals("渠道无可用通道",
                MediaErrorMessages.message("No available channel for model sd_2.0 under group"));
        Assertions.assertEquals("渠道鉴权失败",
                MediaErrorMessages.message("401 unauthorized: invalid api key"));
        Assertions.assertEquals("渠道限流，请稍后重试",
                MediaErrorMessages.message("429 rate limit exceeded"));
        Assertions.assertEquals("供应商响应超时",
                MediaErrorMessages.message("Upstream request timeout"));
    }

    @Test
    void unknownFailureKeepsRawSummary() {
        var humanized = MediaErrorMessages.humanize("some weird gateway error 0x5f");
        Assertions.assertTrue(humanized.message().contains("0x5f"));
        Assertions.assertEquals("some weird gateway error 0x5f", humanized.raw());
        Assertions.assertTrue(MediaErrorMessages.message(null).isBlank());
    }
}
