package com.zwx.zwxagent.creative.support;

import java.util.List;
import java.util.Locale;

/**
 * 供应商失败的「可读原因」映射（P1 收尾）：
 * 原始错误对用户没有行动价值，这里按常见失败面归类，保留原文供诊断区展开。
 * 只做展示映射，不改变存储的错误原文与任何状态判断。
 */
public final class MediaErrorMessages {

    private record Rule(List<String> keywords, String message, String hint) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(List.of("duration", "seconds", "时长", "second"), "模型不支持该视频时长",
                    "对照模型档位（如 5/10 秒）调整镜头目标时长后重试"),
            new Rule(List.of("resolution", "size", "尺寸", "分辨率", "width", "height"),
                    "模型不支持该画面尺寸", "检查画幅/尺寸设置是否在模型支持范围内"),
            new Rule(List.of("moderation", "sensitive", "审核", "违规", "risk", "content policy"),
                    "内容未通过供应商安全审核", "调整提示词中的敏感描述后重试"),
            new Rule(List.of("insufficient", "balance", "arrearage", "quota", "余额", "欠费", "配额"),
                    "供应商余额或配额不足", "到供应商控制台充值或提高配额"),
            new Rule(List.of("model_not_found", "no available channel", "channel", "无可用"),
                    "渠道无可用通道", "确认模型已启用且分组有可用渠道"),
            new Rule(List.of("unauthorized", "invalid api key", "401", "403", "鉴权", "认证失败"),
                    "渠道鉴权失败", "检查供应商 API Key 是否有效/过期"),
            new Rule(List.of("rate limit", "429", "too many requests", "限流"),
                    "渠道限流，请稍后重试", "降低并发或稍后重试"),
            new Rule(List.of("timeout", "timed out", "超时"), "供应商响应超时", "稍后重试；持续超时联系渠道"));

    private MediaErrorMessages() {
    }

    public record Humanized(String message, String hint, String raw) {
    }

    /** 映射失败原文：命中规则给可读原因 + 下一步提示；未命中保留原文摘要。 */
    public static Humanized humanize(String raw) {
        if (raw == null || raw.isBlank()) return new Humanized("", "", "");
        String lower = raw.toLowerCase(Locale.ROOT);
        for (Rule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                    return new Humanized(rule.message(), rule.hint(), raw);
                }
            }
        }
        String summary = raw.strip();
        if (summary.length() > 200) summary = summary.substring(0, 200) + "…";
        return new Humanized(summary, "", raw);
    }

    /** 便捷：只要可读原因。 */
    public static String message(String raw) {
        return humanize(raw).message();
    }
}
