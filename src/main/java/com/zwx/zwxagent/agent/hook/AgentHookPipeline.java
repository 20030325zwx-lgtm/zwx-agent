package com.zwx.zwxagent.agent.hook;

import com.zwx.zwxagent.agent.BaseAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 有序 hook 管线：Spring 启动时收集容器内全部 {@link AgentHook} Bean，按 order 排序、同 id 去重。
 * 单个观察型 hook 异常只记 warn 不影响主流程；{@link HookAbortException} 按语义向上传播。
 * agent 实例多为手工构建的非 Spring Bean，通过 {@link AgentHooks} 静态装配点取用本管线。
 */
@Slf4j
@Component
public class AgentHookPipeline {

    final Map<String, AgentHook> hooks = new LinkedHashMap<>();

    @Autowired
    public AgentHookPipeline(List<AgentHook> registered) {
        registered.stream()
                .sorted(Comparator.comparingInt(AgentHook::order))
                .forEach(hook -> {
                    AgentHook existing = hooks.putIfAbsent(hook.id(), hook);
                    if (existing != null) {
                        log.warn("[agent-hook] 重复 id 的 hook 被忽略：{}（保留 order={} 的 {}）", hook.id(), existing.order(), existing.getClass().getSimpleName());
                    }
                });
        if (!hooks.isEmpty()) {
            log.info("[agent-hook] 已注册 {} 个 hook：{}", hooks.size(), hooks.values().stream().map(AgentHook::id).collect(Collectors.joining(", ")));
        }
    }

    /** agent 实例非 Spring Bean，通过静态装配点提供管线；无 Spring 上下文（单测）时保持 null。 */
    @jakarta.annotation.PostConstruct
    void install() {
        AgentHooks.install(this);
        log.info("[agent-hook] pipeline 已装配到 AgentHooks，注册 hook 数：{}", hooks.size());
    }

    public void fireBeforeRun(AgentRunContext context) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.beforeRun(context);
            } catch (HookAbortException exception) {
                throw exception;
            } catch (Exception exception) {
                log.warn("[agent-hook] beforeRun 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireBeforeStep(AgentRunContext context) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.beforeStep(context);
            } catch (Exception exception) {
                log.warn("[agent-hook] beforeStep 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireAfterStep(AgentRunContext context, String stepResult) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.afterStep(context, stepResult);
            } catch (Exception exception) {
                log.warn("[agent-hook] afterStep 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireBeforeThink(AgentRunContext context) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.beforeThink(context);
            } catch (Exception exception) {
                log.warn("[agent-hook] beforeThink 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireAfterThink(AgentRunContext context, boolean toolCallPlanned) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.afterThink(context, toolCallPlanned);
            } catch (Exception exception) {
                log.warn("[agent-hook] afterThink 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    /** 顺序执行 beforeToolCalls；观察/转换型异常跳过该 hook，HookAbortException 向上传播。 */
    public void fireBeforeToolCalls(AgentRunContext context, List<PlannedToolCall> plannedCalls) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.beforeToolCalls(context, plannedCalls);
            } catch (HookAbortException exception) {
                throw exception;
            } catch (Exception exception) {
                log.warn("[agent-hook] beforeToolCalls 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireAfterToolCalls(AgentRunContext context, List<BaseAgent.ToolExecution> executions) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.afterToolCalls(context, executions);
            } catch (Exception exception) {
                log.warn("[agent-hook] afterToolCalls 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireOnFinish(AgentRunContext context) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.onFinish(context);
            } catch (Exception exception) {
                log.warn("[agent-hook] onFinish 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireOnInterrupted(AgentRunContext context, String reason) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.onInterrupted(context, reason);
            } catch (Exception exception) {
                log.warn("[agent-hook] onInterrupted 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireOnError(AgentRunContext context, Exception error) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.onError(context, error);
            } catch (Exception exception) {
                log.warn("[agent-hook] onError 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }

    public void fireAfterRun(AgentRunContext context) {
        for (AgentHook hook : hooks.values()) {
            try {
                hook.afterRun(context);
            } catch (Exception exception) {
                log.warn("[agent-hook] afterRun 由 {} 抛出，已跳过：{}", hook.id(), exception.getMessage());
            }
        }
    }
}
