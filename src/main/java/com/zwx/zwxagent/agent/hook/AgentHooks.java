package com.zwx.zwxagent.agent.hook;

/**
 * AgentHookPipeline 的静态装配点：agent 实例（BaseAgent 子类）多为手工构建的非 Spring Bean，
 * 无法构造注入；Spring 装配的管线实例在启动时 install，agent 运行时读取。
 * 未启动 Spring（单元测试）时返回 null，全部切点为 no-op。
 */
public final class AgentHooks {

    private static volatile AgentHookPipeline pipeline;

    private AgentHooks() {
    }

    public static void install(AgentHookPipeline installed) {
        pipeline = installed;
    }

    public static AgentHookPipeline pipeline() {
        return pipeline;
    }
}
