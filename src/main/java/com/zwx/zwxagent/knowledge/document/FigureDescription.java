package com.zwx.zwxagent.knowledge.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 图纸视觉增强结果（入库侧）：由视觉模型对 figure 图片产出的结构化描述。
 * 「图内可见文字」与「推断」强制分区——前者是像素里真实存在的文字，可当事实用；
 * 后者是模型的解读，回答阶段若命中原图应由视觉模型复核。
 */
public record FigureDescription(String type,
                                String summary,
                                List<String> nodes,
                                List<String> flows,
                                List<String> visibleText,
                                List<String> inferred,
                                List<String> questions,
                                boolean truncated) {

    public boolean hasContent() {
        return (summary != null && !summary.isBlank())
                || (nodes != null && !nodes.isEmpty())
                || (flows != null && !flows.isEmpty())
                || (visibleText != null && !visibleText.isEmpty());
    }

    public static FigureDescription empty() {
        return new FigureDescription("", "", List.of(), List.of(), List.of(), List.of(), List.of(), false);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 宽松解析：剥离 markdown 代码围栏，取首个 JSON 对象；解析失败返回 empty（调用方降级为仅图注）。 */
    public static FigureDescription parse(String raw) {
        if (raw == null || raw.isBlank()) return empty();
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int start = text.indexOf('\n', fence);
            int end = text.lastIndexOf("```");
            if (start >= 0 && end > start) text = text.substring(start + 1, end).trim();
        }
        int braceStart = text.indexOf('{');
        int braceEnd = text.lastIndexOf('}');
        if (braceStart < 0 || braceEnd <= braceStart) return empty();
        try {
            JsonNode node = MAPPER.readTree(text.substring(braceStart, braceEnd + 1));
            return new FigureDescription(
                    textValue(node, "type"),
                    textValue(node, "summary"),
                    stringList(node, "nodes"),
                    stringList(node, "flows"),
                    stringList(node, "visibleText"),
                    stringList(node, "inferred"),
                    stringList(node, "questions"),
                    node.path("truncated").asBoolean(false));
        } catch (Exception exception) {
            return empty();
        }
    }

    private static String textValue(JsonNode node, String field) {
        String value = node.path(field).asText("");
        return value.isBlank() ? "" : value.trim();
    }

    private static List<String> stringList(JsonNode node, String field) {
        List<String> values = new ArrayList<>();
        JsonNode array = node.path(field);
        if (array.isArray()) {
            for (JsonNode item : array) {
                String value = item.asText("");
                if (!value.isBlank()) values.add(value.trim());
            }
        }
        return List.copyOf(values);
    }
}
