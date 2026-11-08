package com.zwx.zwxagent.platform.intent;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 编排路由策略 v2（2026-11-08）：独立 LLM 意图分类器退出主链路（参考 deepseek-harness 与
 * ZCode——两者都不养前置意图分类器），改为「用户显式信号 + 确定性匹配 + 默认单循环」。
 *
 * <p>设计原则：
 * <ul>
 *   <li>信号从贵到便宜：用户显式（工作区/带图）> 确定性匹配（并行证据）> 默认；不再有一次
 *       额外的串行 LLM 调用</li>
 *   <li>单循环是默认档：线性任务一个循环完成，前序发现自然流向后续步骤；直答不再是前置分类
 *       而是单循环的自然结果（模型不调工具即结束）</li>
 *   <li>图编排的准入条件是「存在可并行的子任务单元」，不是「复杂」——复杂但线性的任务拆开
 *       只会丢上下文（Anthropic《Building Effective Agents》的判据）</li>
 *   <li>保守匹配：并行证据只认强信号（编号列表 ≥3 项 / 显式批量词），误放进图编排的代价
 *       只是多几次模型调用（结果仍正确），误扣留单循环的代价是串行变慢</li>
 * </ul>
 */
public final class OrchestrationRouting {

    /** 单循环=false 即图编排档；reason 面向用户透明展示（prefaceActivity）。 */
    public record Decision(boolean singleLoop, String reason) {
    }

    private static final Pattern NUMBERED_ITEM = Pattern.compile("(?m)^\\s*(\\d{1,2})[.、)）]");
    private static final Pattern BATCH_WORDS =
            Pattern.compile("批量|逐个|挨个|每个都|每张|分别对|分别生|分别查|分别测");

    private OrchestrationRouting() {
    }

    public static Decision decide(boolean workspaceRequest, boolean hasImages, String message,
                                  boolean singleLoopEnabled) {
        if (!singleLoopEnabled) {
            return new Decision(false, "单循环档已关闭（app.agent.single-loop.enabled=false）");
        }
        if (workspaceRequest) {
            return new Decision(false, "项目工作区请求：多智能体编排");
        }
        if (hasImages) {
            return new Decision(false, "带图任务：多智能体编排");
        }
        if (hasParallelEvidence(message)) {
            return new Decision(false, "检测到并行子任务信号：多智能体编排");
        }
        return new Decision(true, "默认单循环：直接执行，需要时调用工具");
    }

    /** 并行证据（只认强信号，详见类注释）。 */
    static boolean hasParallelEvidence(String message) {
        if (message == null || message.isBlank()) return false;
        Set<Integer> items = new HashSet<>();
        var matcher = NUMBERED_ITEM.matcher(message);
        while (matcher.find()) {
            try {
                items.add(Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // 超长编号不是列表
            }
        }
        if (items.size() >= 3) return true;
        return BATCH_WORDS.matcher(message).find();
    }
}
