package com.zwx.zwxagent.agent.core;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 流式 think 聚合器：把关闭内置工具执行后的原始 ChatResponse 分块
 * （文本增量 + 工具调用 delta 片段）聚合为与 .call() 返回值行为等价的一个 ChatResponse。
 * <p>
 * Spring AI 1.0.0 的 MessageAggregator 只聚合文本与 usage，不合并工具调用 delta；
 * 本类按「同 id 追加 / 空 id 续写上一条」合并（OpenAI 与 DashScope 的流式工具调用均按
 * 调用顺序分块下发，实践中不会交错）。文本增量边收边回调给调用方（实时转 SSE），
 * 聚合结果仅在流结束后构建。
 */
public final class ThinkStreamAggregator {

    private ThinkStreamAggregator() {
    }

    /**
     * 阻塞消费流并聚合。stopCheck 返回 true 时提前中止订阅（已收内容照常聚合返回）。
     *
     * @throws IllegalStateException 流完全为空（无文本、无工具调用、无 usage）时抛出，
     *         供调用方走既有重试路径
     */
    public static ChatResponse collect(Flux<ChatResponse> flux, Consumer<String> textDelta,
                                       BooleanSupplier stopCheck) {
        StringBuilder text = new StringBuilder();
        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        Usage lastUsage = null;
        try (Stream<ChatResponse> stream = flux.toStream()) {
            var iterator = stream.takeWhile(chunk -> !stopCheck.getAsBoolean()).iterator();
            while (iterator.hasNext()) {
                ChatResponse chunk = iterator.next();
                if (chunk == null || chunk.getResults() == null || chunk.getResults().isEmpty()) continue;
                AssistantMessage output = chunk.getResult().getOutput();
                if (output == null) continue;
                String delta = output.getText();
                if (delta != null && !delta.isEmpty()) {
                    text.append(delta);
                    if (textDelta != null) textDelta.accept(delta);
                }
                mergeToolCalls(calls, output.getToolCalls());
                Usage chunkUsage = chunk.getMetadata() == null ? null : chunk.getMetadata().getUsage();
                if (chunkUsage != null) lastUsage = chunkUsage;
            }
        }
        if (text.isEmpty() && calls.isEmpty() && lastUsage == null) {
            throw new IllegalStateException("模型返回空流");
        }
        // 本轮 think 结束：空串作为冲刷信号，短文本不必等 worker 整轮结束才可见
        if (textDelta != null) textDelta.accept("");
        AssistantMessage aggregated = new AssistantMessage(text.toString(), Map.of(), List.copyOf(calls));
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder();
        if (lastUsage != null) {
            metadata.usage(new DefaultUsage(lastUsage.getPromptTokens(), lastUsage.getCompletionTokens(),
                    lastUsage.getTotalTokens()));
        }
        return new ChatResponse(List.of(new Generation(aggregated)), metadata.build());
    }

    private static void mergeToolCalls(List<AssistantMessage.ToolCall> calls,
                                       List<AssistantMessage.ToolCall> fragments) {
        if (fragments == null || fragments.isEmpty()) return;
        for (AssistantMessage.ToolCall fragment : fragments) {
            if (fragment == null) continue;
            String id = fragment.id() == null ? "" : fragment.id();
            if (!id.isEmpty()) {
                AssistantMessage.ToolCall existing = findById(calls, id);
                if (existing != null) {
                    calls.set(calls.indexOf(existing), mergeInto(existing, fragment));
                } else {
                    calls.add(fragment);
                }
            } else if (!calls.isEmpty()) {
                // 续写块：无 id，参数片段续接到上一条（流式按调用顺序下发）
                AssistantMessage.ToolCall last = calls.get(calls.size() - 1);
                calls.set(calls.size() - 1, mergeInto(last, fragment));
            }
        }
    }

    private static AssistantMessage.ToolCall findById(List<AssistantMessage.ToolCall> calls, String id) {
        for (AssistantMessage.ToolCall call : calls) {
            if (id.equals(call.id())) return call;
        }
        return null;
    }

    private static AssistantMessage.ToolCall mergeInto(AssistantMessage.ToolCall target,
                                                       AssistantMessage.ToolCall fragment) {
        String name = target.name() == null || target.name().isEmpty()
                ? fragment.name() : target.name();
        String arguments = (target.arguments() == null ? "" : target.arguments())
                + (fragment.arguments() == null ? "" : fragment.arguments());
        return new AssistantMessage.ToolCall(target.id(), target.type(), name, arguments);
    }
}
