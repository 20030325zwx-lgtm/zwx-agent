package com.zwx.zwxagent.capability.tools;

import com.zwx.zwxagent.agent.execution.AgentRunService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 结构化提问：缺关键信息时向用户提一个具体问题，run 进入「等待补充信息」
 * （panel 与对话流据此渲染可回答的卡片，而不是让模型在长回答里埋问题）。
 */
public class AskHumanTool {

    private final AgentRunService agentRunService;
    private final String runId;

    public AskHumanTool(AgentRunService agentRunService, String runId) {
        this.agentRunService = agentRunService;
        this.runId = runId;
    }

    @Tool(description = """
            Ask the user exactly one concrete question when a required input is missing
            (for example which file, which date range, which environment). The run is marked as
            waiting for input and the user sees an answerable card. Do NOT use it for confirming
            file writes or commands; those use the confirmation flow. After calling it, finish the
            turn with a short note instead of guessing.""")
    public String askHuman(
            @ToolParam(description = "One concrete question for the user, in their language") String question,
            @ToolParam(description = "Optional JSON array of suggested answers, e.g. [\"本周\",\"本月\"]", required = false) String options) {
        if (question == null || question.isBlank()) {
            return "Error: question is required";
        }
        if (runId == null || runId.isBlank()) {
            return "Error: no active run to attach the question to";
        }
        agentRunService.markWaitingInput(runId, question.trim());
        return "已向用户提问，本轮进入等待补充信息状态。问题：" + question.trim()
                + (options == null || options.isBlank() ? "" : "（备选：" + options + "）");
    }
}
