package com.zwx.zwxagent.platform.intent;

import java.util.Optional;

/**
 * 意图分流器：对用户消息做一次低成本结构化判断。
 * 实现必须做到"任何失败返回 empty"——调用方按现状行为执行，分流失败绝不阻断主链路。
 */
public interface IntentRouter {

    Optional<IntentDecision> classify(String message);
}
