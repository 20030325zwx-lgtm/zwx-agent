package com.zwx.zwxagent.knowledge.vector;

import org.springframework.ai.document.Document;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion（k=60）：pgvector 路径的服务端混合召回等价实现
 * （Weaviate 原生 Hybrid 的 FusionType.RANKED 服务端即此公式），保证两个 provider
 * 在 {@code KnowledgeVectorStore#hybridSearch} 上返回同尺度的融合分。
 */
public final class ReciprocalRankFusion {

    private ReciprocalRankFusion() {
    }

    public static List<Document> fuse(List<Document> vector, List<Document> lexical) {
        Map<String, Hit> fused = new LinkedHashMap<>();
        addRanked(fused, vector);
        addRanked(fused, lexical);
        return fused.values().stream()
                .sorted(java.util.Comparator.comparingDouble(Hit::score).reversed())
                .map(hit -> Document.builder().id(hit.document().getId()).text(hit.document().getText())
                        .metadata(hit.document().getMetadata()).score(hit.score()).build())
                .toList();
    }

    private static void addRanked(Map<String, Hit> fused, List<Document> documents) {
        for (int index = 0; index < documents.size(); index++) {
            Document document = documents.get(index);
            String id = document.getId() == null ? document.getText() : document.getId();
            Hit existing = fused.get(id);
            double score = 1.0 / (60 + index + 1);
            fused.put(id, existing == null ? new Hit(document, score) : new Hit(existing.document(), existing.score() + score));
        }
    }

    private record Hit(Document document, double score) {
    }
}
