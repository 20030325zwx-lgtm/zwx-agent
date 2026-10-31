package com.zwx.zwxagent.platform.workflow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * 评审 A2（Infinite Canvas 对照，2026-10-31）：必填参数契约纯函数——
 * 多约束 AND、组内备选 OR、{{引用}} 表达式算已填、无契约类型放行。
 */
class WorkflowGraphValidationTest {

    @Test
    void singleRequiredParam() {
        Assertions.assertNull(WorkflowService.missingRequired("llm_text", Map.of("prompt", "写一段文案")));
        Assertions.assertNotNull(WorkflowService.missingRequired("llm_text", Map.of("prompt", "  ")), "空白视为缺失");
        Assertions.assertNotNull(WorkflowService.missingRequired("llm_text", Map.of()));
        Assertions.assertNotNull(WorkflowService.missingRequired("llm_text", null));
    }

    @Test
    void eitherOrGroup() {
        Assertions.assertNull(WorkflowService.missingRequired("poster_render",
                Map.of("image", "{{search_image.output}}")));
        Assertions.assertNull(WorkflowService.missingRequired("poster_render", Map.of("imageAssetId", "asset-1")));
        Assertions.assertNotNull(WorkflowService.missingRequired("poster_render", Map.of("copy", "{}")),
                "只有 copy 不满足 image|imageAssetId");
    }

    @Test
    void andGroupsBothRequired() {
        Assertions.assertNull(WorkflowService.missingRequired("media_image_edit",
                Map.of("prompt", "试穿", "imageAssetIds", "{{start.output.topAssetId}}")));
        Assertions.assertNotNull(WorkflowService.missingRequired("media_image_edit", Map.of("prompt", "试穿")),
                "缺 imageAssetIds");
        Assertions.assertNotNull(WorkflowService.missingRequired("media_image_edit", Map.of("imageAssetIds", "a")),
                "缺 prompt");
    }

    @Test
    void referenceExpressionCountsAsFilled() {
        Assertions.assertNull(WorkflowService.missingRequired("search_image", Map.of("keyword", "{{input.keyword}}")));
        Assertions.assertNull(WorkflowService.missingRequired("file_export",
                Map.of("content", "{{llm_text.output}}")));
    }

    @Test
    void unconstrainedTypesPassThrough() {
        Assertions.assertNull(WorkflowService.missingRequired("novel_context", Map.of()));
        Assertions.assertNull(WorkflowService.missingRequired("novel_context", Map.of("summaryCount", 3)));
        Assertions.assertNull(WorkflowService.missingRequired("start", Map.of()));
        Assertions.assertNull(WorkflowService.missingRequired("unknown_type", null), "未知类型不在契约内，交由类型白名单拒绝");
    }
}
