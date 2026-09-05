package com.zwx.zwxagent.agent;

import cn.hutool.core.util.StrUtil;
import com.zwx.zwxagent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 * <p>
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    private static final long MAX_RUN_MILLIS = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_SAME_TOOL_RESULT_CALLS = 53;
    private static final int MAX_NO_PROGRESS_ROUNDS = 5;

    // 核心属性
    private String name;

    // 提示词
    private String systemPrompt;
    private String nextStepPrompt;

    // 代理状态
    private AgentState state = AgentState.IDLE;

    // 执行步骤控制
    private int currentStep = 0;
    private int maxSteps = 10;

    // LLM 大模型
    private ChatClient chatClient;

    // Memory 记忆（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    private volatile Future<?> activeStep;
    private volatile String stopReason;

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        // 1、基础校验
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("Cannot run agent from state: " + this.state);
        }
        if (StrUtil.isBlank(userPrompt)) {
            throw new RuntimeException("Cannot run agent with empty user prompt");
        }
        // 2、执行，更改状态
        this.state = AgentState.RUNNING;
        // 记录消息上下文
        messageList.add(new UserMessage(userPrompt));
        // 保存结果列表
        List<String> results = new ArrayList<>();
        try {
            // 执行循环
            for (int i = 0; i < maxSteps && state != AgentState.FINISHED; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("Executing step {}/{}", stepNumber, maxSteps);
                // 单步执行
                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);
            }
            // 检查是否超出步骤限制
            if (currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("Terminated: Reached max steps (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("error executing agent", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 3、清理资源
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public SseEmitter runStream(String userPrompt) {
        return runStream(userPrompt, result -> {});
    }

    public SseEmitter runStream(String userPrompt, Consumer<RunResult> completionHandler) {
        return runStream(userPrompt, completionHandler, null);
    }

    public SseEmitter runStream(String userPrompt, Consumer<RunResult> completionHandler, java.util.concurrent.Executor executor) {
        // 创建一个超时时间较长的 SseEmitter
        // 比执行硬时限多留少量余量，确保执行线程能先输出中断总结。
        SseEmitter sseEmitter = new SseEmitter(MAX_RUN_MILLIS + TimeUnit.SECONDS.toMillis(5));
        // 使用线程异步处理，避免阻塞主线程；优先使用专用执行器，避免占用公共池
        java.util.concurrent.Executor runExecutor = executor != null ? executor : java.util.concurrent.ForkJoinPool.commonPool();
        CompletableFuture.runAsync(() -> {
            // 1、基础校验
            try {
                if (this.state != AgentState.IDLE) {
                    sseEmitter.send("错误：无法从状态运行代理：" + this.state);
                    sseEmitter.complete();
                    return;
                }
                if (StrUtil.isBlank(userPrompt)) {
                    sseEmitter.send("错误：不能使用空提示词运行代理");
                    sseEmitter.complete();
                    return;
                }
            } catch (Exception e) {
                sseEmitter.completeWithError(e);
                return;
            }
            // 2、执行，更改状态
            this.state = AgentState.RUNNING;
            this.stopReason = null;
            // 记录消息上下文
            messageList.add(new UserMessage(userPrompt));
            // 保存结果列表
            List<String> results = new ArrayList<>();
            List<String> activities = new ArrayList<>();
            StringBuilder answer = new StringBuilder();
            boolean hasUserVisibleResponse = false;
            long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MAX_RUN_MILLIS);
            Map<String, Integer> sameToolResultCounts = new HashMap<>();
            Set<String> observedToolResults = new HashSet<>();
            int noProgressRounds = 0;
            try {
                // 执行循环
                for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                    if (System.nanoTime() >= deadlineNanos) {
                        requestStop("已达到 5 分钟执行时限");
                        break;
                    }
                    int stepNumber = i + 1;
                    currentStep = stepNumber;
                    log.info("Executing step {}/{}", stepNumber, maxSteps);
                    // 单步执行
                    String stepResult = runStepBeforeDeadline(deadlineNanos);
                    if (stopReason != null) break;
                    String result = "Step " + stepNumber + ": " + stepResult;
                    results.add(result);
                    if (streamStepAsActivity()) {
                        activities.add(result);
                        sseEmitter.send(SseEmitter.event().name("activity").data(result));
                        boolean madeProgress = false;
                        for (ToolExecution execution : lastToolExecutions()) {
                            String resultFingerprint = execution.name() + "\n" + normalize(execution.result());
                            int duplicateCount = sameToolResultCounts.merge(resultFingerprint, 1, Integer::sum);
                            if (duplicateCount >= MAX_SAME_TOOL_RESULT_CALLS) {
                                requestStop("同一工具返回相同结果已累计 " + MAX_SAME_TOOL_RESULT_CALLS + " 次");
                                break;
                            }
                            if (isMeaningful(execution.result()) && observedToolResults.add(resultFingerprint)) {
                                madeProgress = true;
                            }
                        }
                        if (stopReason != null) break;
                        noProgressRounds = madeProgress ? 0 : noProgressRounds + 1;
                        if (noProgressRounds >= MAX_NO_PROGRESS_ROUNDS) {
                            requestStop("连续 " + MAX_NO_PROGRESS_ROUNDS + " 轮没有获得新的有效结果");
                            break;
                        }
                    } else {
                        sseEmitter.send(stepResult);
                        answer.append(stepResult);
                        hasUserVisibleResponse = true;
                    }
                }
                // 检查是否超出步骤限制
                if (state == AgentState.RUNNING && currentStep >= maxSteps) {
                    requestStop("已达到最大执行轮数（" + maxSteps + "）");
                }
                if (stopReason != null) {
                    String summary = buildInterruptedSummary(stopReason, activities);
                    sseEmitter.send(summary);
                    answer.append(summary);
                    hasUserVisibleResponse = true;
                } else if (!hasUserVisibleResponse && !results.isEmpty()) {
                    String fallbackAnswer = buildInterruptedSummary("未获得可展示的最终回答", activities);
                    sseEmitter.send(fallbackAnswer);
                    answer.append(fallbackAnswer);
                }
                completionHandler.accept(new RunResult(answer.toString(), List.copyOf(activities)));
                // 正常完成
                sseEmitter.send("[DONE]");
                sseEmitter.complete();
            } catch (Exception e) {
                state = AgentState.ERROR;
                log.error("error executing agent", e);
                if (!answer.isEmpty()) {
                    try {
                        completionHandler.accept(new RunResult(answer.toString(), List.copyOf(activities)));
                    } catch (Exception persistenceError) {
                        log.error("failed to persist partial agent answer", persistenceError);
                    }
                }
                try {
                    sseEmitter.send("执行错误：" + e.getMessage());
                    sseEmitter.complete();
                } catch (IOException ex) {
                    sseEmitter.completeWithError(ex);
                }
            } finally {
                // 3、清理资源
                this.cleanup();
            }
        });

        // 设置超时回调
        sseEmitter.onTimeout(() -> {
            requestStop("已达到 5 分钟执行时限");
            this.cleanup();
            log.warn("SSE connection timeout");
        });
        sseEmitter.onError(error -> requestStop("客户端连接已断开"));
        // 设置完成回调
        sseEmitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });
        return sseEmitter;
    }

    /**
     * 定义单个步骤
     *
     * @return
     */
    public abstract String step();

    protected boolean streamStepAsActivity() {
        return false;
    }

    protected List<ToolExecution> lastToolExecutions() {
        return List.of();
    }

    private String runStepBeforeDeadline(long deadlineNanos) throws Exception {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            requestStop("已达到 5 分钟执行时限");
            return "";
        }
        // 直接在执行线程上顺序执行：每步本就依赖上一步结果，异步 + 阻塞等待
        // 只会额外占用一个公共池线程并在并发时导致线程饥饿。
        try {
            return step();
        } finally {
            activeStep = null;
        }
    }

    private void requestStop(String reason) {
        if (stopReason == null) stopReason = reason;
        state = AgentState.FINISHED;
        Future<?> stepFuture = activeStep;
        if (stepFuture != null) stepFuture.cancel(true);
    }

    public void stopForClientDisconnect() {
        requestStop("客户端连接已断开");
    }

    private boolean isMeaningful(String result) {
        String normalized = normalize(result);
        return !normalized.isEmpty()
                && !normalized.startsWith("error")
                && !normalized.startsWith("search_unavailable")
                && !normalized.contains("失败")
                && !normalized.contains("无法");
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private String buildInterruptedSummary(String reason, List<String> activities) {
        StringBuilder summary = new StringBuilder("本次任务已停止：").append(reason).append("。\n\n");
        if (activities.isEmpty()) {
            summary.append("在停止前尚未获得可用的工具结果。请尝试缩小目标、补充输入材料，或换一种处理思路后重新发起。");
            return summary.toString();
        }
        summary.append("已完成 ").append(activities.size()).append(" 项工具操作，当前已完成的工作包括：\n");
        activities.stream().limit(5).forEach(activity -> summary.append("- ").append(activitySummary(activity)).append("\n"));
        if (activities.size() > 5) summary.append("- 其余 ").append(activities.size() - 5).append(" 项操作可在执行过程内查看。\n");
        summary.append("\n建议：请根据以上已完成内容确认下一步；也可以缩小目标、提供更明确的约束，或换一种检索与处理思路后继续。");
        return summary.toString();
    }

    private String activitySummary(String activity) {
        String compact = activity == null ? "" : activity.replaceAll("\\s+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 160) + "...";
    }

    public record RunResult(String answer, List<String> activities) {
    }

    public record ToolExecution(String name, String arguments, String result) {
    }

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 子类可以重写此方法来清理资源
    }
}
