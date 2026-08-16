package com.zwx.zwxagent.app;

import java.util.List;

/** A privacy-conscious summary used to ground image-based knowledge retrieval. */
public record LoveVisionAnalysis(String imageType, String summary, List<String> relationshipSignals,
                                 List<String> uncertainItems, String retrievalQuery, boolean available) {

    public static LoveVisionAnalysis unavailable(String userMessage) {
        return new LoveVisionAnalysis("未识别", "视觉摘要不可用，未将图片内容用于知识库检索。",
                List.of(), List.of("图片内容未能稳定解析，回答不应将其视为事实。"), limit(userMessage, 180), false);
    }

    public LoveVisionAnalysis normalized() {
        return new LoveVisionAnalysis(limit(imageType, 32), limit(summary, 120), limitItems(relationshipSignals, 5, 48),
                limitItems(uncertainItems, 3, 64), limit(retrievalQuery, 180), available);
    }

    private static List<String> limitItems(List<String> items, int maxItems, int maxLength) {
        return items == null ? List.of() : items.stream().filter(item -> item != null && !item.isBlank())
                .limit(maxItems).map(item -> limit(item, maxLength)).toList();
    }

    private static String limit(String value, int maxLength) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }
}
