package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import com.zwx.zwxagent.creative.domain.VerificationStatus;
import com.zwx.zwxagent.creative.repository.MediaModelProfileRepository;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 图片模型解析（C02）：显式 modelRowId 优先，其次「图片生成」专用绑定；
 * 两者都必须挂有效 media_model_profile，能力不匹配直接拒绝（BR03，不回退 chat）。
 * 已知 DashScope 文生图模型在解析时幂等补档案（实施手册 C02“经已知 adapter 映射补 profile”）。
 */
@Service
public class MediaModelResolver {

    public static final String ADAPTER_DASHSCOPE_TEXT2IMAGE = "dashscope-text2image";
    public static final String ADAPTER_OPENAI_IMAGES = "openai-images";
    private static final String OPERATION_TEXT_TO_IMAGE = ParameterSchema.IMAGE_GENERATION;

    private final ModelCatalogService modelCatalogService;
    private final MediaModelProfileRepository profileRepository;
    private final JdbcTemplate jdbcTemplate;

    public MediaModelResolver(ModelCatalogService modelCatalogService,
                              MediaModelProfileRepository profileRepository,
                              JdbcTemplate jdbcTemplate) {
        this.modelCatalogService = modelCatalogService;
        this.profileRepository = profileRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ResolvedMediaModel resolve(String userId, Long explicitModelRowId) {
        return resolve(userId, explicitModelRowId, OPERATION_TEXT_TO_IMAGE);
    }

    /** 按操作解析：档案必须声明该操作能力（text_to_image / image_edit）。 */
    public ResolvedMediaModel resolve(String userId, Long explicitModelRowId, String operation) {
        ModelCatalogService.ImageBinding binding;
        if (explicitModelRowId != null) {
            var selection = modelCatalogService.resolveModelRow(userId, explicitModelRowId);
            binding = new ModelCatalogService.ImageBinding(
                    selection.config(), providerIdOfModelRow(explicitModelRowId), explicitModelRowId);
        } else {
            binding = modelCatalogService.resolveImageBinding(userId)
                    .orElseThrow(() -> CreativeException.modelNotConfigured(
                            "未绑定图片生成模型：请在模型设置中为「图片生成」用途绑定生图模型"));
        }
        var profile = ensureProfile(binding);
        ParameterSchema schema = parseSchema(profile.parameterSchemaJson());
        String requestedOperation = operation == null || operation.isBlank()
                ? OPERATION_TEXT_TO_IMAGE : operation;
        if (!Boolean.TRUE.equals(profile.operations().get(requestedOperation))) {
            throw CreativeException.unsupportedParameter("该模型不支持 " + requestedOperation);
        }
        return new ResolvedMediaModel(binding.providerId(), binding.modelRowId(),
                binding.config().provider(), binding.config().baseUrl(),
                binding.config().modelName(), binding.config().apiKey(), profile.adapterKey(),
                schema, profile.verificationStatus() == VerificationStatus.VERIFIED, profile.revision());
    }

    /**
     * 已知协议→适配器映射：dashscope → dashscope-text2image（真实 Key 已验证），
     * openai-compatible → openai-images（中转站，未经真实验证标 UNVERIFIED）。
     * 生图绑定自动补/刷新默认档案（适配器是 schema 的权威来源）；
     * 已被其他适配器（如测试 Mock）接管的模型尊重现状；未知协议明确拒绝。
     */
    private MediaModelProfileRepository.MediaModelProfileRecord ensureProfile(
            ModelCatalogService.ImageBinding binding) {
        var existing = profileRepository.findByModelRowId(binding.modelRowId());
        if (existing.isPresent()) {
            // R07（评审 2026-10-31）：档案是能力与验证结论的事实来源，解析只创建、不覆盖——
            // 否则人工维护的能力开关/验证状态会在每次 resolve 时被默认值冲掉，revision 也失真。
            // 既有 openai-images 档案的 image_edit 能力由 V12 一次性回填。
            return existing.get();
        }
        String protocol = String.valueOf(binding.config().provider());
        String adapterKey;
        ParameterSchema schema;
        VerificationStatus verification;
        java.util.Map<String, Boolean> operations = java.util.Map.of(OPERATION_TEXT_TO_IMAGE, true);
        switch (protocol.toLowerCase()) {
            case "dashscope" -> {
                adapterKey = ADAPTER_DASHSCOPE_TEXT2IMAGE;
                schema = ParameterSchema.dashscopeTextToImage();
                // 2026-10-29 已用真实 Key 验证 wanx2.1 系列出图（见模型契约文档）
                verification = VerificationStatus.VERIFIED;
            }
            case "openai-compatible" -> {
                adapterKey = ADAPTER_OPENAI_IMAGES;
                schema = ParameterSchema.openAiImages();
                // 中转站模型未经真实出图验证，不标 VERIFIED；支持文生图与参考图编辑两个操作
                verification = VerificationStatus.UNVERIFIED;
                operations = Map.of(OPERATION_TEXT_TO_IMAGE, true, "image_edit", true);
            }
            default -> throw CreativeException.modelNotConfigured(
                    "该模型没有可用的图片生成适配器：" + binding.config().provider() + "/"
                            + binding.config().modelName());
        }
        profileRepository.upsert(binding.modelRowId(), adapterKey, "1",
                operations,
                Map.of("operation", ParameterSchema.IMAGE_GENERATION,
                        "parameters", schema.parameters()),
                verification, Instant.now());
        return profileRepository.findByModelRowId(binding.modelRowId()).orElseThrow();
    }

    private ParameterSchema parseSchema(Map<String, Object> schemaJson) {
        ParameterSchema fallback = ParameterSchema.dashscopeTextToImage();
        if (schemaJson == null || schemaJson.isEmpty()) return fallback;
        Object parameters = schemaJson.get("parameters");
        if (!(parameters instanceof List<?> list)) return fallback;
        try {
            String json = CreativeJson.toJson(list);
            var specs = CreativeJson.fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<
                    java.util.List<com.zwx.zwxagent.creative.domain.ParameterSpec>>() { });
            return new ParameterSchema(
                    String.valueOf(schemaJson.getOrDefault("operation", ParameterSchema.IMAGE_GENERATION)), specs);
        } catch (Exception exception) {
            return fallback;
        }
    }

    private long providerIdOfModelRow(long modelRowId) {
        Long providerId = jdbcTemplate.query(
                "SELECT provider_id FROM model_provider_model WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null, modelRowId);
        if (providerId == null) throw new IllegalArgumentException("模型条目不存在");
        return providerId;
    }
}
