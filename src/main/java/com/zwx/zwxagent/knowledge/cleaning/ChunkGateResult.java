package com.zwx.zwxagent.knowledge.cleaning;

import org.springframework.ai.document.Document;

import java.util.List;

/** 切片门禁结果：chunks 为规范化后保留的切片，统计随文档落库。 */
public record ChunkGateResult(List<Document> chunks, ChunkGateStats stats) {

    public ChunkGateResult {
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        stats = stats == null ? ChunkGateStats.empty(chunks.size()) : stats;
    }

    public static ChunkGateResult passthrough(List<Document> chunks) {
        List<Document> values = chunks == null ? List.of() : chunks;
        return new ChunkGateResult(values, ChunkGateStats.empty(values.size()));
    }
}
