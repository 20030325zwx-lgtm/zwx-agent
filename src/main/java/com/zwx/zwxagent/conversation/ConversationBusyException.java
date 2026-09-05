package com.zwx.zwxagent.conversation;

public class ConversationBusyException extends RuntimeException {
    public ConversationBusyException() {
        super("该会话正在生成回复，请等待完成后再发送新消息");
    }
}
