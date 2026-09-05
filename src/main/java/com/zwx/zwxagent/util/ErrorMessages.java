package com.zwx.zwxagent.util;

import java.util.Locale;

public final class ErrorMessages {

    private ErrorMessages() {
    }

    public static String describe(Throwable error) {
        String raw = error == null || error.getMessage() == null ? "" : error.getMessage();
        String normalized = raw.toLowerCase(Locale.ROOT);
        if (normalized.contains("429") || normalized.contains("throttl") || normalized.contains("rate limit") || normalized.contains("ratelimit")) {
            return "模型服务限流中，请稍后重试。已保留已生成内容。";
        }
        if (normalized.contains("datainspection") || normalized.contains("sensitive") || normalized.contains("内容安全") || normalized.contains("content filter")) {
            return "内容未通过平台安全审查，请调整表述后重试。";
        }
        if (normalized.contains("invalidapikey") || normalized.contains("unauthorized") || normalized.contains("401")) {
            return "模型服务鉴权失败，请检查 API Key 配置。";
        }
        if (normalized.contains("timeout") || normalized.contains("timed out") || normalized.contains("超时")) {
            return "模型响应超时，请稍后重试。已保留已生成内容。";
        }
        if (error instanceof com.zwx.zwxagent.conversation.ConversationBusyException
                || error instanceof com.zwx.zwxagent.conversation.LoveConversationService.DuplicateRequestException
                || error instanceof com.zwx.zwxagent.conversation.AgentConversationService.DuplicateRequestException) {
            return raw;
        }
        return "生成中断：服务或网络异常，已保留已生成内容，可点击继续或重新生成。";
    }
}
