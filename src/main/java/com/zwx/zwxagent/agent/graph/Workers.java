package com.zwx.zwxagent.agent.graph;

import com.zwx.zwxagent.agent.core.ToolCallAgent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 活跃 worker 注册表（进程级，按 runKey=conversationId 隔离）：主循环的 spawnSubagent
 * 子代理与遗留 worker 执行机制共享，用户停止请求经 stopAll 覆盖全部派生单元。
 * 原为 WorkersNode 实例字段；编排层退役后（2026-11-08）提升为独立进程级注册表。
 */
final class Workers {

    private static final Map<String, List<ToolCallAgent>> ACTIVE_WORKERS = new ConcurrentHashMap<>();

    private Workers() {
    }

    static void registerWorker(String runKey, ToolCallAgent worker) {
        ACTIVE_WORKERS.computeIfAbsent(runKey, ignored -> new CopyOnWriteArrayList<>()).add(worker);
    }

    static void unregisterWorker(String runKey, ToolCallAgent worker) {
        List<ToolCallAgent> workers = ACTIVE_WORKERS.get(runKey);
        if (workers != null) {
            workers.remove(worker);
            if (workers.isEmpty()) ACTIVE_WORKERS.remove(runKey, workers);
        }
    }

    /** 停止指定会话的全部活跃 worker（用户取消/断线）。 */
    static void stopAll(String conversationId) {
        List<ToolCallAgent> workers = ACTIVE_WORKERS.get(conversationId);
        if (workers != null) workers.forEach(ToolCallAgent::stopForClientDisconnect);
    }

    static String knowledgePrompt(String knowledge) {
        if (knowledge == null || knowledge.isBlank()) return "";
        return "【本轮检索到的私有知识资料】\n" + knowledge
                + "\n\n知识约束：回答优先依据这些资料，关键结论使用资料中的 [文件名#切片号] 引用。"
                + "如果资料与问题无关或不足以回答：先说明知识库未覆盖相关内容，然后区分情况处理——"
                + "通用知识、图片内容或可推理的问题，继续基于自身知识正常回答（注明来自自身知识、建议核实，不使用切片号引用）；"
                + "涉及公司内部事实与制度的问题，如实说明资料中暂无规定，可建议用户向相关部门确认。"
                + "任何情况下都不要只回复\"知识库中没有\"就草草结束，如能给用户有帮助的补充信息或下一步建议，请一并说明。\n\n";
    }

    static String compact(String value, int max) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
