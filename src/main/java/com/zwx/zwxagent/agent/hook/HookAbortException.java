package com.zwx.zwxagent.agent.hook;

/**
 * 拦截型 hook 主动终止当前动作的唯一手段，message 必须是面向用户的原因文案。
 * beforeToolCalls 抛出 → 本 step 以受控文案结束；beforeRun 抛出 → 本次 run 受控中断（走 onInterrupted）。
 */
public class HookAbortException extends RuntimeException {

    public HookAbortException(String userFacingReason) {
        super(userFacingReason);
    }
}
