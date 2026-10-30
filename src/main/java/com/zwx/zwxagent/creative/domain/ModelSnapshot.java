package com.zwx.zwxagent.creative.domain;

import java.util.List;

/**
 * 冻结的模型身份（BR19：任务受理后不随全局绑定变化）。
 * 不包含密钥；凭据在调用时按 providerId 取当前有效值。
 */
public record ModelSnapshot(long providerId, long modelRowId, String providerName, String protocol,
                            String baseUrl, String modelName, String adapterKey,
                            List<String> capabilities, long catalogRevision) {

    public ModelSnapshot {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
