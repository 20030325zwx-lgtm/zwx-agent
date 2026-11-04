package com.zwx.zwxagent.agent.execution;

/** 提示词来源：节点在构造时拿到它，运行时按 key 取当前生效版本的内容（缺省回退内置文本）。 */
@FunctionalInterface
public interface PromptSource {

    String resolve(String key, String fallback);
}
