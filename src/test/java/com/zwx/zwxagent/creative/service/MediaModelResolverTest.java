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

    @Test
    void rejectsForeignModelRow() {
        // 其他用户显式引用属于管理员的模型行：resolveModelRow 拒绝（无权操作该供应商）
        String ownerId = ownerOfDashScopeProvider();
        Assertions.assertNotEquals(ownerId, "user-without-image-binding");
        Assertions.assertThrows(RuntimeException.class,
                () -> resolver.resolve("user-without-image-binding", modelRowOfDashScope()));
    }

    private String ownerOfDashScopeProvider() {
        // 以 wanx2.1-t2i-plus 所在供应商的归属为准，避免多个 dashscope 供应商时取错
        return jdbcTemplate.queryForObject("""
                SELECT p.owner_id FROM model_provider p
                JOIN model_provider_model m ON m.provider_id = p.id
                WHERE m.model_id = 'wanx2.1-t2i-plus'
                """, String.class);
    }

    private long modelRowOfDashScope() {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM model_provider_model WHERE model_id = 'wanx2.1-t2i-plus'", Long.class);
        Assertions.assertNotNull(id);
        return id;
    }
}
