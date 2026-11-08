package com.zwx.zwxagent.capability.tools;

import cn.hutool.core.collection.CollUtil;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolExecutionResult;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * 并行工具执行管理器：同一 step 内模型发出的多个工具调用在线程池中并行执行（单调用退化为
 * 直调 delegate，零开销）。这是「子代理 = 工具」架构的执行前提——模型在一个 assistant
 * turn 里发 N 个 spawnSubagent，串行执行会把并行收益变成等待总和。
 *
 * <p>实现：按调用粒度拆分（每个单调用走完整 delegate 路径，保留框架的异常包装/空结果语义），
 * 聚合 ToolResponseMessage 并按原顺序拼回 conversationHistory。跨线程恢复幂等账本的
 * worker 身份：主线程按调用顺序预分配 invocation 序号（重放幂等的前提），子线程
 * {@link ToolCallWorkerContext#restore} 恢复。
 */
public final class ParallelizingToolCallingManager implements ToolCallingManager {

    /**
     * 共享并行池（cached + daemon）：必须独立于 workerExecutor——spawn 子代理同步跑在
     * 并行池线程上，其内部的并行工具调用再向同一池提交，共用固定池会互相饿死（死锁）。
     */
    private static final Executor SHARED_POOL = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "tool-parallel");
        thread.setDaemon(true);
        return thread;
    });

    private final ToolCallingManager delegate;
    private final Executor executor;

    public ParallelizingToolCallingManager() {
        this(ToolCallingManager.builder().build(), SHARED_POOL);
    }

    public ParallelizingToolCallingManager(ToolCallingManager delegate, Executor executor) {
        this.delegate = delegate;
        this.executor = executor;
    }

    @Override
    public List<org.springframework.ai.tool.definition.ToolDefinition> resolveToolDefinitions(
            org.springframework.ai.model.tool.ToolCallingChatOptions options) {
        return delegate.resolveToolDefinitions(options);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        AssistantMessage assistant = chatResponse.getResult().getOutput();
        List<AssistantMessage.ToolCall> calls = assistant.getToolCalls();
        if (calls == null || calls.size() <= 1) {
            return delegate.executeToolCalls(prompt, chatResponse);
        }
        ToolCallWorkerContext.WorkerSnapshot snapshot = ToolCallWorkerContext.capture();
        List<Future<ToolResponseMessage.ToolResponse>> futures = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            final AssistantMessage.ToolCall call = calls.get(index);
            final int ordinalForCall = index + 1;
            // 单调用响应：assistant 消息只携带当前调用，delegate 走完整执行路径
            AssistantMessage singleAssistant = new AssistantMessage(
                    assistant.getText() == null ? "" : assistant.getText(),
                    assistant.getMetadata(), List.of(call));
            ChatResponse singleResponse = new ChatResponse(
                    List.of(new org.springframework.ai.chat.model.Generation(singleAssistant)),
                    chatResponse.getMetadata());
            FutureTask<ToolResponseMessage.ToolResponse> task = new FutureTask<>((Callable<ToolResponseMessage.ToolResponse>) () -> {
                ToolCallWorkerContext.restore(snapshot, ordinalForCall);
                try {
                    ToolExecutionResult single = delegate.executeToolCalls(prompt, singleResponse);
                    ToolResponseMessage response =
                            (ToolResponseMessage) CollUtil.getLast(single.conversationHistory());
                    return response.getResponses().isEmpty()
                            ? new ToolResponseMessage.ToolResponse(call.id(), call.name(), "")
                            : response.getResponses().get(response.getResponses().size() - 1);
                } finally {
                    ToolCallWorkerContext.clear();
                }
            });
            futures.add(task);
            try {
                executor.execute(task);
            } catch (RuntimeException rejected) {
                task.cancel(false);
                futures.set(index, completed(new ToolResponseMessage.ToolResponse(
                        call.id(), call.name(), "工具执行队列已满，未能启动：" + call.name())));
            }
        }
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>(calls.size());
        for (Future<ToolResponseMessage.ToolResponse> future : futures) {
            try {
                responses.add(future.get());
            } catch (java.util.concurrent.ExecutionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                responses.add(new ToolResponseMessage.ToolResponse("", "unknown",
                        "工具执行异常：" + cause.getMessage()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                futures.forEach(f -> f.cancel(true));
                throw new IllegalStateException("工具并行执行被中断", exception);
            }
        }
        List<Message> history = new ArrayList<>(prompt.getInstructions());
        history.add(assistant);
        history.add(new ToolResponseMessage(responses, Map.of()));
        return new DefaultToolExecutionResult(history, false);
    }

    private Future<ToolResponseMessage.ToolResponse> completed(ToolResponseMessage.ToolResponse response) {
        FutureTask<ToolResponseMessage.ToolResponse> task = new FutureTask<>(() -> response);
        task.run();
        return task;
    }
}
