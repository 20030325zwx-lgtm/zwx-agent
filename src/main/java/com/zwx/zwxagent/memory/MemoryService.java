package com.zwx.zwxagent.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 长期记忆服务（design/plans/2026-09-08-memory-skills-workspace.md §3.3 批次 C）：
 * LLM 异步抽取用户事实并按 (tenant, agent, fact_hash) 去重落库；
 * 注入侧按"最近优先 + prompt 关键词命中加权"返回 top 事实。
 * 提取开关默认关（LLM 成本考虑）；所有失败只记日志，绝不影响对话主链路。
 */
@Slf4j
@Service
public class MemoryService {

    static final int MAX_FACTS_PER_TURN = 3;
    static final int MAX_FACT_CHARS = 500;
    static final int SCAN_WINDOW = 50;

    private final JdbcTemplate jdbcTemplate;
    private final ChatClient chatClient;
    private final boolean extractionEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService extractionExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "memory-extraction");
        thread.setDaemon(true);
        return thread;
    });

    public MemoryService(JdbcTemplate jdbcTemplate,
                         ChatModel dashscopeChatModel,
                         @Value("${app.agent.memory.extraction-enabled:false}") boolean extractionEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatClient = dashscopeChatModel == null ? null : ChatClient.builder(dashscopeChatModel).build();
        this.extractionEnabled = extractionEnabled;
    }

    /** 异步抽取本轮问答中的用户事实并落库；开关关闭 / 身份缺失 / 内容为空时静默跳过。 */
    public void extractAsync(String tenantId, String agentKey, String userId, String conversationId,
                             String userPrompt, String answer) {
        if (!extractionEnabled) return;
        if (isBlank(tenantId) || isBlank(agentKey) || isBlank(answer)) return;
        String prompt = userPrompt == null ? "" : userPrompt;
        String reply = answer.length() > 3000 ? answer.substring(0, 3000) : answer;
        try {
            extractionExecutor.execute(() -> {
                try {
                    List<String> facts = parseFactsJson(callModel(prompt, reply));
                    for (String fact : facts) {
                        upsertFact(tenantId, agentKey, userId, conversationId, fact);
                    }
                    log.info("[memory] 事实提取完成（tenant={}, agent={}）：{} 条", tenantId, agentKey, facts.size());
                } catch (Exception cause) {
                    log.warn("[memory] 事实提取失败（不影响对话）：{}", cause.getMessage());
                }
            });
        } catch (RejectedExecutionException rejected) {
            log.warn("[memory] 提取队列已满，放弃本轮提取");
        }
    }

    /** 最近优先 + prompt 关键词命中加权，返回 top 事实文本。 */
    public List<String> factsFor(String tenantId, String agentKey, String userPrompt, int limit) {
        if (isBlank(tenantId) || isBlank(agentKey) || limit <= 0) return List.of();
        List<String> recent;
        try {
            recent = jdbcTemplate.query(
                    "SELECT fact FROM agent_memory_fact WHERE tenant_id = ? AND agent_key = ? " +
                            "ORDER BY created_at DESC, id DESC LIMIT " + SCAN_WINDOW,
                    (resultSet, index) -> resultSet.getString(1),
                    tenantId, agentKey);
        } catch (Exception cause) {
            log.warn("[memory] 事实查询失败：{}", cause.getMessage());
            return List.of();
        }
        return rankFacts(recent, userPrompt, limit);
    }

    void upsertFact(String tenantId, String agentKey, String userId, String conversationId, String fact) {
        String normalized = normalizeFact(fact);
        if (normalized.isEmpty()) return;
        jdbcTemplate.update(
                "INSERT INTO agent_memory_fact (tenant_id, agent_key, user_id, fact, fact_hash, source_conversation_id) " +
                        "VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (tenant_id, agent_key, fact_hash) DO UPDATE SET last_seen_at = CURRENT_TIMESTAMP",
                tenantId, agentKey, userId, normalized, sha256Hex(normalized), conversationId);
    }

    String callModel(String userPrompt, String answer) {
        if (chatClient == null) throw new IllegalStateException("ChatModel 不可用");
        String instruction = """
                从下面的对话中提取关于用户的持久事实（偏好、约束、背景信息），用于跨会话个性化。
                要求：
                1. 用户请求里明确表达的偏好（例如"请记住：我喜欢……"、"我要……"）必须提取；
                2. 只提取明确表达或可确定的事实，不要推测；
                3. 每条事实一句话、可独立理解；
                4. 最多 %d 条；确实没有任何可提取事实时输出 []。
                只输出 JSON 字符串数组，不要输出其他内容。
                """.formatted(MAX_FACTS_PER_TURN);
        return chatClient.prompt()
                .system(instruction)
                .user("用户请求：" + userPrompt + "\n\n助手回答：" + answer)
                .call()
                .content();
    }

    /** 宽容解析 LLM 输出：剥 markdown 围栏、截取 JSON 数组、兼容字符串项与 {fact: ...} 项。解析失败返回空列表。 */
    static List<String> parseFactsJson(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        String text = raw.trim();
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode array = mapper.readTree(text.substring(start, end + 1));
            if (!array.isArray()) return List.of();
            List<String> facts = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (JsonNode item : array) {
                String fact = item.isTextual() ? item.asText()
                        : item.isObject() && item.hasNonNull("fact") ? item.get("fact").asText() : null;
                if (fact == null) continue;
                String normalized = normalizeFact(fact);
                if (normalized.isEmpty() || !seen.add(normalized)) continue;
                facts.add(normalized);
                if (facts.size() >= MAX_FACTS_PER_TURN) break;
            }
            return facts;
        } catch (Exception cause) {
            return List.of();
        }
    }

    /** 归一化：去首尾空白 + 压缩连续空白；超长截断。 */
    static String normalizeFact(String fact) {
        if (fact == null) return "";
        String normalized = fact.replaceAll("\\s+", " ").trim();
        return normalized.length() > MAX_FACT_CHARS ? normalized.substring(0, MAX_FACT_CHARS) : normalized;
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte item : hash) hex.append(Character.forDigit((item >> 4) & 0xF, 16)).append(Character.forDigit(item & 0xF, 16));
            return hex.toString();
        } catch (Exception cause) {
            throw new IllegalStateException("SHA-256 unavailable", cause);
        }
    }

    /**
     * 纯函数排序：recency 顺序传入（越靠前越新），prompt 关键词命中每条 +100，
     * 同分保持 recency 顺序，取前 limit 条。
     */
    static List<String> rankFacts(List<String> recentFacts, String userPrompt, int limit) {
        if (recentFacts == null || recentFacts.isEmpty() || limit <= 0) return List.of();
        Set<String> keywords = extractKeywords(userPrompt);
        List<Map.Entry<String, Integer>> scored = new ArrayList<>();
        for (int index = 0; index < recentFacts.size(); index++) {
            String fact = recentFacts.get(index);
            int score = Math.max(0, SCAN_WINDOW - index);
            String comparable = fact.toLowerCase(Locale.ROOT);
            int hits = 0;
            for (String keyword : keywords) {
                if (comparable.contains(keyword) && hits < 3) {
                    score += 100;
                    hits++;
                }
            }
            scored.add(Map.entry(fact, score));
        }
        scored.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : scored) {
            result.add(entry.getKey());
            if (result.size() >= limit) break;
        }
        return result;
    }

    /** 抽取 prompt 关键词：CJK 连续段（长度>=2）与拉丁词（长度>=2），去重。 */
    static Set<String> extractKeywords(String userPrompt) {
        if (userPrompt == null || userPrompt.isBlank()) return Set.of();
        Set<String> keywords = new LinkedHashSet<>();
        StringBuilder current = new StringBuilder();
        boolean cjkRun = false;
        for (int index = 0; index <= userPrompt.length(); index++) {
            boolean cjk = index < userPrompt.length() && isCjk(userPrompt.charAt(index));
            boolean latin = index < userPrompt.length() && Character.isLetterOrDigit(userPrompt.charAt(index)) && !cjk;
            if (cjk) {
                if (!cjkRun && current.length() > 0) {
                    flushLatin(keywords, current);
                    current.setLength(0);
                }
                cjkRun = true;
                current.append(userPrompt.charAt(index));
            } else if (latin) {
                if (cjkRun && current.length() > 0) {
                    flushCjk(keywords, current);
                    current.setLength(0);
                }
                cjkRun = false;
                current.append(userPrompt.charAt(index));
            } else {
                if (cjkRun) flushCjk(keywords, current);
                else flushLatin(keywords, current);
                current.setLength(0);
                cjkRun = false;
            }
        }
        return keywords;
    }

    private static void flushCjk(Set<String> keywords, StringBuilder run) {
        String text = run.toString();
        for (int length = Math.min(3, text.length()); length >= 2; length--) {
            for (int start = 0; start + length <= text.length(); start++) {
                keywords.add(text.substring(start, start + length));
            }
        }
    }

    private static void flushLatin(Set<String> keywords, StringBuilder run) {
        String word = run.toString().toLowerCase(Locale.ROOT);
        if (word.length() >= 2) keywords.add(word);
    }

    private static boolean isCjk(char candidate) {
        return candidate >= 0x4E00 && candidate <= 0x9FFF;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 仅供生命周期收尾；Spring 单例销毁时调用。 */
    @jakarta.annotation.PreDestroy
    void shutdown() {
        extractionExecutor.shutdownNow();
    }
}
