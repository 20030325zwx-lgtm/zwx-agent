package com.zwx.zwxagent.platform.workflow;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 运行输入声明（R03 完整版）：声明校验与 required 业务化报错。 */
class WorkflowInputsValidationTest {

    private Map<String, Object> field(String key, String label, String type, boolean required) {
        Map<String, Object> map = new HashMap<>();
        map.put("key", key);
        map.put("label", label);
        map.put("type", type);
        map.put("required", required);
        return map;
    }

    @Test
    void validateInputsNormalizesAndRejectsBadKeys() {
        Assertions.assertTrue(WorkflowService.validateInputs(null).isEmpty());
        Assertions.assertTrue(WorkflowService.validateInputs(List.of()).isEmpty());
        List<Map<String, Object>> ok = WorkflowService.validateInputs(List.of(
                field("keyword", "商品关键词", "text", true),
                Map.of("key", "imageAssetId", "label", "商品图", "type", "asset", "required", "true")));
        Assertions.assertEquals(2, ok.size());
        Assertions.assertEquals(Boolean.TRUE, ok.get(1).get("required"), "字符串 true 也接受");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> WorkflowService.validateInputs(List.of(field("Key!", "关键词", "text", true))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> WorkflowService.validateInputs(List.of(
                        field("keyword", "关键词", "text", true), field("keyword", "重复", "text", true))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> WorkflowService.validateInputs(List.of(field("keyword", "", "text", true))));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> WorkflowService.validateInputs(List.of(field("keyword", "关键词", "number", true))));
    }

    @Test
    void missingDeclaredInputsReportsLabels() {
        List<Map<String, Object>> inputs = List.of(
                field("keyword", "商品关键词", "text", true),
                field("imageAssetId", "选定商品图", "asset", true),
                field("title", "章节标题", "text", false));
        Assertions.assertNull(WorkflowService.missingDeclaredInputs(inputs,
                Map.of("keyword", "卫衣", "imageAssetId", "asset-1")));
        Assertions.assertEquals("商品关键词、选定商品图",
                WorkflowService.missingDeclaredInputs(inputs, Map.of()));
        Assertions.assertNotNull(WorkflowService.missingDeclaredInputs(inputs,
                Map.of("keyword", " ", "imageAssetId", "asset-1")), "空白算缺失");
        Assertions.assertNull(WorkflowService.missingDeclaredInputs(List.of(), Map.of()));
    }
}
