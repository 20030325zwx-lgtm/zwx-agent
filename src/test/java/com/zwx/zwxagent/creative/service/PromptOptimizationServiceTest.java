package com.zwx.zwxagent.creative.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** A26（C09）：优化结果的容错解析——合法 JSON、代码围栏、纯文本与空输出。 */
class PromptOptimizationServiceTest {

    @Test
    void extractsPromptFromJson() {
        Assertions.assertEquals("雨后的霓虹街角",
                PromptOptimizationService.extractPrompt("{\"prompt\":\"雨后的霓虹街角\"}"));
    }

    @Test
    void extractsPromptFromFencedJsonWithProse() {
        String raw = "好的，以下是优化结果：\n```json\n{\"prompt\":\"一只橘猫坐在窗台上\"}\n```";
        Assertions.assertEquals("一只橘猫坐在窗台上", PromptOptimizationService.extractPrompt(raw));
    }

    @Test
    void malformedJsonFallsBackToPlainText() {
        String raw = "```text  {prompt: 这是不合法 JSON 的文本  ```";
        String value = PromptOptimizationService.extractPrompt(raw);
        Assertions.assertFalse(value.isBlank(), "畸形 JSON 不能丢原文");
        Assertions.assertFalse(value.contains("```"), "应剔除代码围栏");
    }

    @Test
    void blankOutputYieldsEmpty() {
        Assertions.assertEquals("", PromptOptimizationService.extractPrompt(null));
        Assertions.assertEquals("", PromptOptimizationService.extractPrompt("   "));
    }
}
