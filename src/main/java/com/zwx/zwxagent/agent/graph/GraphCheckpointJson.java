package com.zwx.zwxagent.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphCheckpoint 与 JSON（Map 形态）的互转：v2 追加式快照的 state_json 编解码。
 * 字段名即 state_json 的键，读端容忍缺字段（向后兼容旧快照）。
 */
public final class GraphCheckpointJson {

    public static final int SCHEMA_VERSION = 1;

    private GraphCheckpointJson() {
    }

    public static Map<String, Object> write(GraphCheckpoint checkpoint) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("lastNode", checkpoint.lastNode());
        state.put("nextNode", checkpoint.nextNode());
        state.put("plan", checkpoint.plan());
        state.put("taskResults", checkpoint.taskResults());
        state.put("activities", checkpoint.activities());
        state.put("events", checkpoint.events());
        state.put("revision", checkpoint.revision());
        state.put("verificationStatus", checkpoint.verificationStatus());
        state.put("verificationFeedback", checkpoint.verificationFeedback());
        state.put("finalAnswer", checkpoint.finalAnswer());
        state.put("partialAnswer", checkpoint.partialAnswer());
        state.put("reviseSteps", checkpoint.reviseSteps());
        return state;
    }

    public static GraphCheckpoint read(ObjectMapper mapper, String stateJson) {
        try {
            JsonNode root = mapper.readTree(stateJson == null || stateJson.isBlank() ? "{}" : stateJson);
            List<PlanStep> plan = readList(mapper, root.get("plan"), PlanStep.class);
            List<String> taskResults = readStringList(root.get("taskResults"));
            List<String> activities = readStringList(root.get("activities"));
            List<ActivityEvent> events = readList(mapper, root.get("events"), ActivityEvent.class);
            List<Integer> reviseSteps = readIntList(root.get("reviseSteps"));
            return new GraphCheckpoint(
                    text(root, "lastNode"),
                    text(root, "nextNode"),
                    plan,
                    taskResults,
                    activities,
                    events,
                    root.path("revision").asInt(0),
                    text(root, "verificationStatus"),
                    text(root, "verificationFeedback"),
                    text(root, "finalAnswer"),
                    text(root, "partialAnswer"),
                    reviseSteps);
        } catch (Exception exception) {
            throw new IllegalStateException("v2 检查点反序列化失败", exception);
        }
    }

    public static Map<String, Object> summary(GraphCheckpoint checkpoint) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("lastNode", checkpoint.lastNode());
        summary.put("planSize", checkpoint.plan().size());
        summary.put("resultSize", checkpoint.taskResults().size());
        summary.put("revision", checkpoint.revision());
        summary.put("verificationStatus", checkpoint.verificationStatus());
        summary.put("finalAnswerChars", checkpoint.finalAnswer() == null ? 0 : checkpoint.finalAnswer().length());
        return summary;
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static <T> List<T> readList(ObjectMapper mapper, JsonNode node, Class<T> type) {
        if (node == null || node.isNull()) return List.of();
        try {
            List<T> list = new ArrayList<>();
            for (JsonNode item : node) {
                list.add(mapper.treeToValue(item, type));
            }
            return list;
        } catch (Exception exception) {
            throw new IllegalStateException("检查点列表反序列化失败：" + type.getSimpleName(), exception);
        }
    }

    private static List<String> readStringList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        List<String> list = new ArrayList<>();
        for (JsonNode item : node) list.add(item.isNull() ? "" : item.asText(""));
        return list;
    }

    private static List<Integer> readIntList(JsonNode node) {
        if (node == null || node.isNull()) return List.of();
        List<Integer> list = new ArrayList<>();
        for (JsonNode item : node) list.add(item.asInt());
        return list;
    }
}
