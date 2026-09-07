package com.zwx.zwxagent.agent.hook;

/**
 * 计划中的工具调用（beforeToolCalls 切点传入的可变视图）。
 * 转换型 hook 可修改 arguments / 从计划中移除；id 与 type 保持不变以维持 Spring AI 工具调用协议。
 */
public final class PlannedToolCall {

    private final String id;
    private final String type;
    private final String name;
    private String arguments;

    public PlannedToolCall(String id, String type, String name, String arguments) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.arguments = arguments;
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String name() {
        return name;
    }

    public String arguments() {
        return arguments;
    }

    public void arguments(String value) {
        this.arguments = value;
    }
}
