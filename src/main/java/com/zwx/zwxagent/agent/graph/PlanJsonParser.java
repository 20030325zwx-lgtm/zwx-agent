package com.zwx.zwxagent.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 planner 输出的 JSON 计划。容忍 ```json 围栏、前后缀文字；解析失败时回退为单个 general 子任务。
 */
public final class PlanJsonParser {

    static final int MAX_STEPS = 4;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PlanJsonParser() {
    }

    public static List<PlanStep> parse(String llmOutput, String userRequest) {
        String json = extractJsonArray(llmOutput);
        if (json != null) {
            try {
                JsonNode root = MAPPER.readTree(json);
                if (root.isArray() && !root.isEmpty()) {
                    List<PlanStep> steps = new ArrayList<>();
                    for (JsonNode item : root) {
                        String title = item.path("title").asText("子任务");
                        WorkerRole role = WorkerRole.fromKey(item.path("role").asText("general"));
                        String detail = item.path("detail").asText("");
                        steps.add(new PlanStep(title, role, detail.isEmpty()
                                ? "完成：" + title
                                : detail));
                        if (steps.size() >= MAX_STEPS) break;
                    }
                    if (!steps.isEmpty()) return List.copyOf(steps);
                }
            } catch (Exception ignored) {
                // fall through to fallback
            }
        }
        return List.of(new PlanStep("整体完成任务", WorkerRole.GENERAL,
                "直接完成用户请求：" + userRequest));
    }

    /** 从 LLM 输出里提取第一个形如 JSON 数组的片段；没有则返回 null。 */
    static String extractJsonArray(String text) {
        if (text == null) return null;
        String withoutFence = text.replaceAll("```(json|JSON)", "").replace("```", "");
        int start = withoutFence.indexOf('[');
        int end = withoutFence.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        return withoutFence.substring(start, end + 1);
    }
}
