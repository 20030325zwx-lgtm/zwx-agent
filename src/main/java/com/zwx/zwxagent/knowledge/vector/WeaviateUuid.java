package com.zwx.zwxagent.knowledge.vector;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Weaviate 对象 ID 归一化：业务侧确定性 ID（{@code UUID.nameUUIDFromBytes}，v3 形态）
 * 直接透传；非法字符串回退为按字节的确定性 UUID，保证同一 Chunk 重试写入仍是同一对象（幂等）。
 */
public final class WeaviateUuid {

    private WeaviateUuid() {
    }

    public static String of(String id) {
        if (id == null || id.isBlank()) return UUID.randomUUID().toString();
        String trimmed = id.trim();
        try {
            return UUID.fromString(trimmed).toString();
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(trimmed.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
}
