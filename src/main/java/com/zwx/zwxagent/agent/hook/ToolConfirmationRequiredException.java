package com.zwx.zwxagent.agent.hook;

/**
 * 工具调用需要用户人工确认时抛出：继承 HookAbortException，
 * 本 step 以受控文案结束（模型能看到「等待确认」并自行收尾），run 随后在编排层挂起。
 */
public class ToolConfirmationRequiredException extends HookAbortException {

    public ToolConfirmationRequiredException(String userFacingReason) {
        super(userFacingReason);
    }
}
