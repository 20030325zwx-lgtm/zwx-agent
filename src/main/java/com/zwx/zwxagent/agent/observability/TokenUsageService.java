package com.zwx.zwxagent.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 超级智能体 token 计量台账：按 run 汇总每次模型调用的真实 token 与提示词构成，
 * 用于回答"成本花在哪一段"。只统计，不干预生成；run 结束即释放。
 */
@Service
public class TokenUsageService {

    private static final int MAX_LEDGERS = 200;

    private final Map<String, Ledger> ledgers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public TokenUsageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 一次模型调用的观测样本。
     * toolResultChars 单独计量：工具调用的入参与返回都放在消息的结构字段里，getText() 读不到，
     * 而多轮工具循环每轮都会把历史结果整段重发，是提示词膨胀的真正来源。
     */
    public record CallSample(int promptTokens, int completionTokens, int systemChars,
                             int toolSchemaChars, int toolCount, int messageChars, int messageCount,
                             int toolResultChars) {
    }

    private static final class Ledger {
        private int ragContextChars;
        private final Map<String, Phase> phases = new LinkedHashMap<>();

        private synchronized void record(String phase, CallSample sample) {
            phases.computeIfAbsent(phase, ignored -> new Phase()).add(sample);
        }

        private synchronized void bindContext(int ragContextChars) {
            this.ragContextChars = ragContextChars;
        }
    }

    private static final class Phase {
        private int calls;
        private int promptTokens;
        private int completionTokens;
        private long systemChars;
        private long toolSchemaChars;
        private int maxToolCount;
        private long messageChars;
        private long maxMessageChars;
        private long messageCount;
        private long toolResultChars;
        private long maxToolResultChars;

        void add(CallSample sample) {
            calls++;
            promptTokens += sample.promptTokens();
            completionTokens += sample.completionTokens();
            systemChars += sample.systemChars();
            toolSchemaChars += sample.toolSchemaChars();
            maxToolCount = Math.max(maxToolCount, sample.toolCount());
            messageChars += sample.messageChars();
            maxMessageChars = Math.max(maxMessageChars, sample.messageChars());
            messageCount += sample.messageCount();
            toolResultChars += sample.toolResultChars();
            maxToolResultChars = Math.max(maxToolResultChars, sample.toolResultChars());
        }

        Map<String, Object> toJson(String name) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("phase", name);
            node.put("calls", calls);
            node.put("promptTokens", promptTokens);
            node.put("completionTokens", completionTokens);
            node.put("avgSystemChars", avg(systemChars));
            node.put("avgToolSchemaChars", avg(toolSchemaChars));
            node.put("maxToolCount", maxToolCount);
            node.put("avgMessageChars", avg(messageChars));
            node.put("maxMessageChars", (int) maxMessageChars);
            node.put("avgMessageCount", avg(messageCount));
            node.put("avgToolResultChars", avg(toolResultChars));
            node.put("maxToolResultChars", (int) maxToolResultChars);
            return node;
        }

        private int avg(long sum) {
            return calls == 0 ? 0 : (int) (sum / calls);
        }
    }

    /** 记录本次 run 注入给各子任务的私有知识上下文规模（同一段可能被复制多份）。 */
    public void bindContext(String runId, int ragContextChars) {
        if (runId == null || runId.isBlank()) return;
        if (ledgers.size() > MAX_LEDGERS) {
            ledgers.keySet().stream().filter(key -> !key.equals(runId)).findFirst().ifPresent(ledgers::remove);
        }
        ledgers.computeIfAbsent(runId, ignored -> new Ledger()).bindContext(ragContextChars);
    }

    public void record(String runId, String phase, CallSample sample) {
        if (runId == null || runId.isBlank() || sample == null) return;
        ledgers.computeIfAbsent(runId, ignored -> new Ledger()).record(phase, sample);
    }

    public int promptTokens(String runId) {
        return sum(runId, true);
    }

    public int completionTokens(String runId) {
        return sum(runId, false);
    }

    public int calls(String runId) {
        Ledger ledger = runId == null ? null : ledgers.get(runId);
        if (ledger == null) return 0;
        synchronized (ledger) {
            return ledger.phases.values().stream().mapToInt(phase -> phase.calls).sum();
        }
    }

    /** 按 token 开销降序的分阶段分解，供落库与前端展示。 */
    public String usageJson(String runId) {
        Ledger ledger = runId == null ? null : ledgers.get(runId);
        if (ledger == null) return null;
        List<Map<String, Object>> phases;
        int ragChars;
        synchronized (ledger) {
            ragChars = ledger.ragContextChars;
            phases = ledger.phases.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, Phase>>comparingInt(entry -> entry.getValue().promptTokens).reversed())
                    .map(entry -> entry.getValue().toJson(entry.getKey()))
                    .toList();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("promptTokens", sum(runId, true));
        root.put("completionTokens", sum(runId, false));
        root.put("calls", calls(runId));
        root.put("ragContextChars", ragChars);
        root.put("phases", phases);
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception exception) {
            return null;
        }
    }

    public void release(String runId) {
        if (runId != null && !runId.isBlank()) ledgers.remove(runId);
    }

    private int sum(String runId, boolean prompt) {
        Ledger ledger = runId == null ? null : ledgers.get(runId);
        if (ledger == null) return 0;
        synchronized (ledger) {
            return ledger.phases.values().stream()
                    .mapToInt(phase -> prompt ? phase.promptTokens : phase.completionTokens).sum();
        }
    }
}
