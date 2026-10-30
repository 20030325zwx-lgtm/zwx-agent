package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A03（C02）：图片模型解析——用户绑定、显式模型行、无绑定阻断、越权拒绝。
 * 解析会读取本地/CI 的模型目录数据库；不发起任何生图调用。
 */
@SpringBootTest
@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
class MediaModelResolverTest {

    @Resource
    private MediaModelResolver resolver;

    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void resolvesBoundImageModelForOwner() {
        String ownerId = ownerOfDashScopeProvider();
        ResolvedMediaModel model = resolver.resolve(ownerId, null);
        Assertions.assertEquals("wanx2.1-t2i-plus", model.modelName());
        Assertions.assertEquals(MediaModelResolver.ADAPTER_DASHSCOPE_TEXT2IMAGE, model.adapterKey());
        Assertions.assertTrue(model.verified(), "已实测模型应标记 VERIFIED");
        Assertions.assertFalse(model.apiKey().isBlank());
        // schema 已从档案加载：合法参数应通过校验
        Assertions.assertTrue(model.parameterSchema().validate(java.util.Map.of()).isEmpty());
        Assertions.assertFalse(model.parameterSchema()
                .validate(java.util.Map.of("size", "2000*2000")).isEmpty());
    }

    @Test
    void explicitModelRowWins() {
        String ownerId = ownerOfDashScopeProvider();
        Long turboRowId = jdbcTemplate.queryForObject(
                "SELECT id FROM model_provider_model WHERE model_id = 'wanx2.1-t2i-turbo'", Long.class);
        ResolvedMediaModel model = resolver.resolve(ownerId, turboRowId);
        Assertions.assertEquals("wanx2.1-t2i-turbo", model.modelName());
    }

    @Test
    void blocksUserWithoutBindingInsteadOfFallingBackToChat() {
        String ownerId = ownerOfDashScopeProvider();
        // 其他用户没有个人绑定，也不能使用别人的个人供应商（系统绑定未配置）
        CreativeException exception = Assertions.assertThrows(CreativeException.class,
                () -> resolver.resolve("user-without-image-binding", null));
        Assertions.assertEquals(CreativeException.MODEL_NOT_CONFIGURED, exception.code());
    }

    /**
     * 中转站接入（2026-10-30 决策）：openai-compatible 协议 + image_generation 绑定
     * 自动补 openai-images 档案，UNVERIFIED（未经真实出图验证）；size 为自由 WxH 字符串。
     */
    @Test
    void openAiCompatibleBindingResolvesToOpenAiImagesProfile() {
        String user = "resolver-relay-user";
        long providerId = 900301L;
        long rowId = 900301L;
        jdbcTemplate.update("""
                        INSERT INTO model_provider (id, owner_id, name, protocol, base_url, enabled)
                        VALUES (?, ?, '中转站测试', 'openai-compatible', 'https://relay.example.com/v1', TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """,
                ps -> {
                    ps.setLong(1, providerId);
                    ps.setString(2, user);
                });
        jdbcTemplate.update("""
                        INSERT INTO model_provider_model (id, provider_id, model_id, display_name, enabled)
                        VALUES (?, ?, 'flux-dev', '中转站生图测试', TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """,
                ps -> {
                    ps.setLong(1, rowId);
                    ps.setLong(2, providerId);
                });
        jdbcTemplate.update("""
                        INSERT INTO model_binding (scope_type, owner_id, purpose, provider_id, model_row_id)
                        VALUES ('USER', ?, 'image_generation', ?, ?)
                        ON CONFLICT (scope_type, owner_id, purpose) DO UPDATE
                        SET provider_id = EXCLUDED.provider_id, model_row_id = EXCLUDED.model_row_id
                        """,
                ps -> {
                    ps.setString(1, user);
                    ps.setLong(2, providerId);
                    ps.setLong(3, rowId);
                });
        try {
            ResolvedMediaModel model = resolver.resolve(user, null);
            Assertions.assertEquals(MediaModelResolver.ADAPTER_OPENAI_IMAGES, model.adapterKey());
            Assertions.assertEquals("openai-compatible", model.protocol());
            Assertions.assertEquals("https://relay.example.com/v1", model.baseUrl());
            Assertions.assertFalse(model.verified(), "中转站模型未经真实出图验证，不得标 VERIFIED");
            // size 为自由字符串：非 DashScope 枚举的 WxH 也应通过；watermark 不在 schema 内
            Assertions.assertTrue(model.parameterSchema().validate(java.util.Map.of("size", "1024x1792")).isEmpty());
            Assertions.assertFalse(model.parameterSchema().validate(java.util.Map.of("watermark", true)).isEmpty());
        } finally {
            jdbcTemplate.update("DELETE FROM media_model_profile WHERE model_row_id = ?", rowId);
            jdbcTemplate.update("DELETE FROM model_binding WHERE owner_id = ? AND purpose = 'image_generation'", user);
            jdbcTemplate.update("DELETE FROM model_provider_model WHERE id = ?", rowId);
            jdbcTemplate.update("DELETE FROM model_provider WHERE id = ?", providerId);
        }
    }

    @Test
    void rejectsForeignModelRow() {
        // 其他用户显式引用属于管理员的模型行：resolveModelRow 拒绝（无权操作该供应商）
        String ownerId = ownerOfDashScopeProvider();
        Assertions.assertNotEquals(ownerId, "user-without-image-binding");
        Assertions.assertThrows(RuntimeException.class,
                () -> resolver.resolve("user-without-image-binding", modelRowOfDashScope()));
    }

    private String ownerOfDashScopeProvider() {
        // 本地/CI 库可能存在多个挂 wanx 系列模型的供应商（系统官方 + UI Mock）；
        // 固定取 id 最小的 DashScope 官方供应商，其档案为真实验证的 dashscope 适配器
        return jdbcTemplate.queryForObject("""
                SELECT p.owner_id FROM model_provider p
                JOIN model_provider_model m ON m.provider_id = p.id
                WHERE m.model_id = 'wanx2.1-t2i-plus'
                ORDER BY p.id LIMIT 1
                """, String.class);
    }

    private long modelRowOfDashScope() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT min(id) FROM model_provider_model WHERE model_id = 'wanx2.1-t2i-turbo'", Long.class);
        Assertions.assertNotNull(id);
        return id;
    }
}
