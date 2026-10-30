package com.zwx.zwxagent.creative.domain;

/** 图片生成模型的完整解析结果（含凭据，只在内存中使用，不落日志/不落库）。 */
public record ResolvedMediaModel(long providerId, long modelRowId, String protocol,
                                 String baseUrl, String modelName, String apiKey, String adapterKey,
                                 ParameterSchema parameterSchema, boolean verified, long profileRevision) {
}
