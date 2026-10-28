package com.zwx.zwxagent.agent.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型调用轨迹记录：为每次 LLM 调用落一条可回放的调用级证据（阶段、耗时、token、请求/响应摘要）。
 *
 * <p>默认关闭（{@code app.agent.observability.model-io-enabled=false}），只做 token 计数；
 * 评测或排障时开启。默认存结构摘要与预览，{@code model-io-full=true} 时存完整载荷（单条截断）。</p>
 */
@Slf4j
@Service
public class ModelIoService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;
    private final boolean fullPayloads;
    private final int previewChars;
    private final int maxPayloadChars;
    private final ConcurrentHashMap<String, AtomicInteger> sequences = new ConcurrentHashMap<>();

    public ModelIoService(
            JdbcTemplate jdbcTemplate,
            @Value("${app.agent.observability.model-io-enabled:true}") boolean enabled,
            @Value("${app.agent.observability.model-io-full:false}") boolean fullPayloads,
            @Value("${app.agent.observability.model-io-preview-chars:500}") int previewChars,
            @Value("${app.agent.observability.model-io-max-payload-chars:65536}") int maxPayloadChars) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
        this.fullPayloads = fullPayloads;
        this.previewChars = Math.max(0, previewChars);
        this.maxPayloadChars = Math.max(1024, maxPayloadChars);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 记录一次模型调用。任何异常都不得影响生成。
     */
    public void record(String runId, String phase, String model,
                       List<Message> messages, ChatResponse response, long latencyMs) {
        if (!enabled || runId == null || runId.isBlank()) return;
        try {
            String requestDigest = requestDigest(messages);
            String responseDigest = responseDigest(response);
            int promptTokens = 0;
            int completionTokens = 0;
            if (response != null && response.getMetadata() != null && response.getMetadata().getUsage() != null) {
                Integer prompt = response.getMetadata().getUsage().getPromptTokens();
                Integer completion = response.getMetadata().getUsage().getCompletionTokens();
                promptTokens = prompt == null ? 0 : prompt;
                completionTokens = completion == null ? 0 : completion;
            }
            int sequence = sequences
                    .computeIfAbsent(runId, key -> new AtomicInteger())
                    .incrementAndGet();
            jdbcTemplate.update("""
                    INSERT INTO agent_llm_call (run_id, tenant_id, sequence, phase, model,
                                                prompt_tokens, completion_tokens, latency_ms,
                                                request_digest, response_digest)
                    SELECT ?, r.tenant_id, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb)
                    FROM agent_run r WHERE r.id = ?
                    """, runId, sequence, phase, truncate(model, 128), promptTokens, completionTokens,
                    latencyMs, requestDigest, responseDigest, runId);
        } catch (Exception exception) {
            log.debug("记录模型调用轨迹失败；runId={}, phase={}", runId, phase, exception);
        }
    }

    public void releaseRun(String runId) {
        if (runId != null) sequences.remove(runId);
    }

    private String requestDigest(List<Message> messages) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        int totalChars = 0;
        int systemChars = 0;
        for (Message message : messages == null ? List.<Message>of() : messages) {
            String text = message.getText() == null ? "" : message.getText();
            totalChars += text.length();
            if (message.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM) {
                systemChars += text.length();
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.getMessageType().name());
            item.put("chars", text.length());
            item.put("preview", preview(text));
            if (message instanceof AssistantMessage assistant && !assistant.getToolCalls().isEmpty()) {
                List<Map<String, Object>> calls = new ArrayList<>();
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    Map<String, Object> callItem = new LinkedHashMap<>();
                    callItem.put("name", call.name());
                    callItem.put("argumentsPreview", preview(call.arguments()));
                    calls.add(callItem);
                }
                item.put("toolCalls", calls);
            }
            if (message instanceof ToolResponseMessage toolResponse) {
                List<Map<String, Object>> responses = new ArrayList<>();
                for (ToolResponseMessage.ToolResponse item2 : toolResponse.getResponses()) {
                    Map<String, Object> responseItem = new LinkedHashMap<>();
                    responseItem.put("name", item2.name());
                    responseItem.put("chars", length(item2.responseData()));
                    responseItem.put("preview", preview(item2.responseData()));
                    responses.add(responseItem);
                }
                item.put("toolResponses", responses);
            }
            if (fullPayloads) item.put("content", truncate(text, maxPayloadChars));
            items.add(item);
        }
        Map<String, Object> digest = new LinkedHashMap<>();
        digest.put("messageCount", items.size());
        digest.put("totalChars", totalChars);
        digest.put("systemChars", systemChars);
        digest.put("messages", items);
        return objectMapper.writeValueAsString(digest);
    }

    private String responseDigest(ChatResponse response) throws Exception {
        Map<String, Object> digest = new LinkedHashMap<>();
        if (response == null || response.getResults().isEmpty()) return objectMapper.writeValueAsString(digest);
        Generation generation = response.getResults().get(0);
        String text = generation.getOutput() == null ? "" : generation.getOutput().getText();
        digest.put("chars", length(text));
        digest.put("preview", preview(text));
        if (generation.getMetadata() != null && generation.getMetadata().getFinishReason() != null) {
            digest.put("finishReason", generation.getMetadata().getFinishReason());
        }
        if (generation.getOutput() != null && !generation.getOutput().getToolCalls().isEmpty()) {
            List<Map<String, Object>> calls = new ArrayList<>();
            for (AssistantMessage.ToolCall call : generation.getOutput().getToolCalls()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", call.name());
                item.put("argumentsPreview", preview(call.arguments()));
                calls.add(item);
            }
            digest.put("toolCalls", calls);
        }
        if (fullPayloads) digest.put("content", truncate(text, maxPayloadChars));
        return objectMapper.writeValueAsString(digest);
    }

    private String preview(String value) {
        if (value == null) return "";
        return truncate(value, previewChars);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
