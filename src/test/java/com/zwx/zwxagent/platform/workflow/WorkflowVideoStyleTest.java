package com.zwx.zwxagent.platform.workflow;

import com.zwx.zwxagent.creative.domain.ParameterSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 视频画风拼接（2026-11-05）：画布选中的风格在提交前拼进提示词，
 * 用户原文必须完整保留，预算不足时降级注记而不是截断正文。
 */
class WorkflowVideoStyleTest {

    @Test
    void styleNoteIsAppendedAfterPrompt() {
        String prompt = "雨夜街头，人物转身看向镜头";
        String styled = WorkflowService.withVideoStyle(prompt, "CINEMATIC");
        Assertions.assertTrue(styled.startsWith(prompt), "用户原文不能被改写");
        Assertions.assertTrue(styled.contains("电影感"));
        Assertions.assertTrue(styled.length() > prompt.length());
    }

    @Test
    void unspecifiedOrUnknownStyleKeepsPrompt() {
        String prompt = "海边日落，慢推特写";
        Assertions.assertEquals(prompt, WorkflowService.withVideoStyle(prompt, null));
        Assertions.assertEquals(prompt, WorkflowService.withVideoStyle(prompt, "  "));
        Assertions.assertEquals(prompt, WorkflowService.withVideoStyle(prompt, "NOT_A_STYLE"));
    }

    @Test
    void fullBudgetPromptIsNeverTruncated() {
        String prompt = "字".repeat(ParameterSchema.MODEL_PROMPT_MAX);
        Assertions.assertEquals(prompt, WorkflowService.withVideoStyle(prompt, "CINEMATIC"));
    }

    @Test
    void tightBudgetDegradesToStyleLabel() {
        String prompt = "字".repeat(ParameterSchema.MODEL_PROMPT_MAX - 8);
        String styled = WorkflowService.withVideoStyle(prompt, "CINEMATIC");
        Assertions.assertTrue(styled.startsWith(prompt));
        Assertions.assertTrue(styled.endsWith("电影感画风。"), "放不下完整锚点时只保留风格名");
        Assertions.assertTrue(styled.length() <= ParameterSchema.MODEL_PROMPT_MAX);
    }
}
