package com.zwx.zwxagent.knowledge.image;

import com.zwx.zwxagent.platform.settings.DynamicChatModelFactory;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

/**
 * 字节直传的图片理解（2026-11-05）：对象存储改为内网 MinIO 后，第三方模型无法回源拉取图片，
 * 这里把图片字节直接交给多模态视觉模型，产出与 MCP 分析一致的结果结构，供 agent/工作流复用。
 * 未配置视觉模型或调用失败时返回 unavailable，由调用方按原有降级语义处理。
 */
@Service
public class VisionAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(VisionAnalysisService.class);

    private final ModelCatalogService catalogService;
    private final DynamicChatModelFactory chatModelFactory;

    public VisionAnalysisService(ModelCatalogService catalogService, DynamicChatModelFactory chatModelFactory) {
        this.catalogService = catalogService;
        this.chatModelFactory = chatModelFactory;
    }

    public ImageAnalysisResult analyze(String userId, byte[] bytes, String mimeType, String question) {
        if (bytes == null || bytes.length == 0) return ImageAnalysisResult.unavailable("图片内容为空");
        String prompt = question == null || question.isBlank()
                ? "描述这张图片的主体、场景、文字与要点，供后续对话参考。" : question;
        try {
            var selection = resolveVision(userId);
            if (selection == null) return ImageAnalysisResult.unavailable("未配置可读图的视觉模型");
            ChatClient client = ChatClient.builder(chatModelFactory.multimodalModel(selection.config())).build();
            String answer = client.prompt()
                    .user(spec -> spec.text(prompt)
                            .media(new Media(MimeTypeUtils.parseMimeType(
                                    mimeType == null || mimeType.isBlank() ? "image/png" : mimeType),
                                    new ByteArrayResource(bytes))))
                    .call()
                    .content();
            if (answer == null || answer.isBlank()) return ImageAnalysisResult.unavailable("图片分析返回为空");
            return new ImageAnalysisResult(answer.trim(), "", java.util.List.of(), "", true, "");
        } catch (RuntimeException exception) {
            log.warn("视觉模型图片理解失败：{}", exception.getMessage());
            return ImageAnalysisResult.unavailable("图片分析服务不可用");
        }
    }

    /** vision 用途优先，未绑定时回退 optimize/chat（多数对话模型支持图片输入）。 */
    private ModelCatalogService.ResolvedSelection resolveVision(String userId) {
        try {
            return catalogService.resolve(userId, ModelCatalogService.PURPOSE_VISION);
        } catch (RuntimeException visionUnavailable) {
            try {
                return catalogService.resolveWithFallback(
                        userId, ModelCatalogService.PURPOSE_OPTIMIZE, ModelCatalogService.PURPOSE_CHAT);
            } catch (RuntimeException fallbackUnavailable) {
                return null;
            }
        }
    }
}
