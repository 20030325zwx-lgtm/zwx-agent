package com.zwx.zwxagent.creative.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 冻结的规范化请求输入（BR19）。referenceAssetIds 顺序有意义，不得排序。 */
public record InputSnapshot(String operation, String prompt,
                            Map<String, Object> parameters, List<UUID> referenceAssetIds) {

    public InputSnapshot {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        referenceAssetIds = referenceAssetIds == null ? List.of() : List.copyOf(referenceAssetIds);
    }
}
