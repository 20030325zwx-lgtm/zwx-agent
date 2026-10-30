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
        ModelCatalogService.ImageBinding binding;
        if (explicitModelRowId != null) {
            var selection = modelCatalogService.resolveModelRow(userId, explicitModelRowId);
            binding = new ModelCatalogService.ImageBinding(
                    selection.config(), providerIdOfModelRow(explicitModelRowId), explicitModelRowId);
        } else {
            binding = modelCatalogService.resolveImageBinding(userId)
                    .orElseThrow(() -> CreativeException.modelNotConfigured(
                            "未绑定图片生成模型：请在模型设置中为「图片生成」用途绑定 DashScope 生图模型"));
        }
        var profile = ensureProfile(binding);
        ParameterSchema schema = parseSchema(profile.parameterSchemaJson());
        if (!Boolean.TRUE.equals(profile.operations().get(OPERATION_TEXT_TO_IMAGE))) {
            throw CreativeException.unsupportedParameter("该模型不支持 " + OPERATION_TEXT_TO_IMAGE);
        }
        return new ResolvedMediaModel(binding.providerId(), binding.modelRowId(),
                binding.config().provider(), binding.config().baseUrl(),
                binding.config().modelName(), binding.config().apiKey(), profile.adapterKey(),
                schema, profile.verificationStatus() == VerificationStatus.VERIFIED, profile.revision());
    }

    /**
     * 已知适配器映射：DashScope 协议的生图绑定自动补/刷新默认档案（适配器是 schema 的权威来源）；
     * 已被其他适配器（如测试 Mock）接管的模型尊重现状；其他协议无适配，明确拒绝。
     */
    private MediaModelProfileRepository.MediaModelProfileRecord ensureProfile(
            ModelCatalogService.ImageBinding binding) {
        var existing = profileRepository.findByModelRowId(binding.modelRowId());
        if (existing.isPresent() && !ADAPTER_DASHSCOPE_TEXT2IMAGE.equals(existing.get().adapterKey())) {
            return existing.get();
        }
        if (!"dashscope".equalsIgnoreCase(binding.config().provider())) {
            throw CreativeException.modelNotConfigured(
                    "该模型没有可用的图片生成适配器：" + binding.config().provider() + "/" + binding.config().modelName());
        }
        profileRepository.upsert(binding.modelRowId(), ADAPTER_DASHSCOPE_TEXT2IMAGE, "1",
                Map.of(OPERATION_TEXT_TO_IMAGE, true),
                Map.of("operation", ParameterSchema.IMAGE_GENERATION,
                        "parameters", ParameterSchema.dashscopeTextToImage().parameters()),
                // 2026-10-29 已用真实 Key 验证 wanx2.1 系列出图（见模型契约文档）
                VerificationStatus.VERIFIED, Instant.now());
        return profileRepository.findByModelRowId(binding.modelRowId()).orElseThrow();
    }

    private ParameterSchema parseSchema(Map<String, Object> schemaJson) {
        if (schemaJson == null || schemaJson.isEmpty()) return ParameterSchema.dashscopeTextToImage();
        Object parameters = schemaJson.get("parameters");
        if (!(parameters instanceof List<?> list)) return ParameterSchema.dashscopeTextToImage();
        try {
            String json = CreativeJson.toJson(list);
            var specs = CreativeJson.fromJson(json, new com.fasterxml.jackson.core.type.TypeReference<
                    java.util.List<com.zwx.zwxagent.creative.domain.ParameterSpec>>() { });
            return new ParameterSchema(
                    String.valueOf(schemaJson.getOrDefault("operation", ParameterSchema.IMAGE_GENERATION)), specs);
        } catch (Exception exception) {
            return ParameterSchema.dashscopeTextToImage();
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
