package com.zwx.zwxagent.agent.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 解析 planner 输出的 JSON 计划。容忍 ```json 围栏、前后缀文字；解析失败时回退为单个 general 子任务。
 * 解析后统一规范化：id 按位置重编为 s1..sn（声明 id → 规范 id 建映射），
 * 依赖只保留可解析引用并断环，保证调度时不会死等。
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
                        List<String> dependsOn = toStringList(item.path("depends_on"));
                        String acceptance = item.path("acceptance").asText("");
                        String id = item.path("id").asText("");
                        steps.add(new PlanStep(id, title, role, detail.isEmpty()
                                ? "完成：" + title
                                : detail, dependsOn, acceptance));
                        if (steps.size() >= MAX_STEPS) break;
                    }
                    if (!steps.isEmpty()) return normalize(steps);
                }
            } catch (Exception ignored) {
                // fall through to fallback
            }
        }
        return List.of(new PlanStep("s1", "整体完成任务", WorkerRole.GENERAL,
                "直接完成用户请求：" + userRequest, List.of(), ""));
    }

    /**
     * 规范化计划：id 一律按位置重编为 s1..sn；depends_on 通过「声明 id → 规范 id」映射转换，
     * 未知引用与自引用丢弃；最后断开依赖环（环内步骤退化为并行），保证能分层调度。
     */
    static List<PlanStep> normalize(List<PlanStep> steps) {
        int size = steps.size();
        Map<String, String> declared = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            String declaredId = steps.get(index).id();
            declared.put(declaredId == null || declaredId.isBlank() ? "#pos" + index : declaredId, "s" + (index + 1));
        }
        List<PlanStep> rebuilt = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            PlanStep step = steps.get(index);
            String ownId = "s" + (index + 1);
            List<String> deps = step.dependsOn().stream()
                    .map(dep -> declared.getOrDefault(dep, ""))
                    .filter(dep -> !dep.isEmpty() && !dep.equals(ownId))
                    .distinct()
                    .toList();
            rebuilt.add(new PlanStep(ownId, step.title(), step.role(), step.detail(), deps, step.acceptance()));
        }
        // 断环：反复求解可满足依赖的步骤，仍处在环里的清空依赖退化为并行
        for (int round = 0; round < size; round++) {
            boolean[] resolvable = resolvableFlags(rebuilt);
            boolean cleared = false;
            for (int index = 0; index < size; index++) {
                if (!resolvable[index] && !rebuilt.get(index).dependsOn().isEmpty()) {
                    PlanStep step = rebuilt.get(index);
                    rebuilt.set(index, new PlanStep(step.id(), step.title(), step.role(),
                            step.detail(), List.of(), step.acceptance()));
                    cleared = true;
                }
            }
            if (!cleared) break;
        }
        return List.copyOf(rebuilt);
    }

    private static boolean[] resolvableFlags(List<PlanStep> steps) {
        int size = steps.size();
        boolean[] resolvable = new boolean[size];
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int index = 0; index < size; index++) {
                if (resolvable[index]) continue;
                final int current = index;
                boolean ok = steps.get(current).dependsOn().stream().allMatch(dep -> {
                    int depIndex = indexOfId(steps, dep);
                    return depIndex < 0 || depIndex == current || resolvable[depIndex];
                });
                if (ok) {
                    resolvable[index] = true;
                    changed = true;
                }
            }
        }
        return resolvable;
    }

    private static int indexOfId(List<PlanStep> steps, String id) {
        for (int index = 0; index < steps.size(); index++) {
            if (steps.get(index).id().equals(id)) return index;
        }
        return -1;
    }

    private static List<String> toStringList(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText("")));
        return values;
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
