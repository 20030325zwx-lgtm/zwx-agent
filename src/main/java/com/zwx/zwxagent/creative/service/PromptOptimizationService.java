package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

/**
 * 提示词优化（C09/A26）：只做文本改写，不创建媒体任务、不调用图片供应商。
 * 输出必须可解析；格式异常/超长时明确失败，由界面保留原文让用户手工生成。
 */
@Service
public class PromptOptimizationService {

    public static final int PROMPT_MAX = 8000;

    private static final String SYSTEM = """
            你是文生图提示词编辑。把用户描述改写为一段可直接用于图片生成模型的中文提示词：
            主体、场景、构图、光线、风格要具体，不添加用户未要求的文字水印或品牌。
            只输出 JSON：{"prompt":"改写后的提示词"}，不要输出解释或 Markdown。
            """;

    public record OptimizeRequest(String prompt) {
    }

    public record OptimizeResult(String originalPrompt, String optimizedPrompt, String modelName) {
    }

    private final ModelCatalogService catalogService;
    private final DynamicChatModelFactory chatModelFactory;

    public PromptOptimizationService(ModelCatalogService catalogService,
                                     DynamicChatModelFactory chatModelFactory) {
        this.catalogService = catalogService;
        this.chatModelFactory = chatModelFactory;
    }

    public OptimizeResult optimize(String userId, String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("请输入要优化的提示词");
        }
        String trimmed = prompt.trim();
        if (trimmed.length() > PROMPT_MAX) {
            throw CreativeException.unsupportedParameter("提示词超过 " + PROMPT_MAX + " 个字符");
        }
        var selection = catalogService.resolve(userId, ModelCatalogService.PURPOSE_CHAT);
        String raw;
        try {
            ChatClient client = ChatClient.builder(chatModelFactory.model(selection.config())).build();
            raw = client.prompt().system(SYSTEM).user(trimmed).call().content();
        } catch (Exception exception) {
            throw CreativeException.optimizeFailed("提示词优化失败：" + rootMessage(exception));
        }
        String optimized = extractPrompt(raw);
        if (optimized.isBlank()) {
            throw CreativeException.optimizeFailed("优化结果为空，请重试或直接使用原文");
        }
        if (optimized.length() > ParameterSchema.MODEL_PROMPT_MAX) {
            throw CreativeException.optimizeFailed(
                    "优化结果超过模型上限 " + ParameterSchema.MODEL_PROMPT_MAX + " 字，请缩短描述后重试");
        }
        return new OptimizeResult(trimmed, optimized, selection.config().modelName());
    }

    /** 容忍模型包裹代码块或多余文本：取首个 JSON 对象的 prompt 字段。 */
    static String extractPrompt(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                var node = CreativeJson.parse(text.substring(start, end + 1));
                String value = node.path("prompt").asText("").trim();
                if (!value.isBlank()) return value;
            } catch (Exception ignored) {
                // 非 JSON：按纯文本处理
            }
        }
        // 模型直接返回纯文本时接受，但剔除代码围栏
        return text.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
