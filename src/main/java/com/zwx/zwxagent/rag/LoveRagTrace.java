package com.zwx.zwxagent.rag;

import java.util.List;

public record LoveRagTrace(String query, int topK, double similarityThreshold,
                           List<LoveRetrievalCandidate> candidates, String decision,
                           List<LoveKnowledgeReference> references, String model, boolean streaming,
                           boolean degraded, String rerankModel) {
    public LoveRagTrace {
        if (degraded && decision == null) {
            decision = "知识库检索不可用，模型仅使用系统提示词与会话上下文回答。";
        }
    }

    /** 兼容未启用重排序时的旧调用点：rerankModel 为 null 表示未重排。 */
    public LoveRagTrace(String query, int topK, double similarityThreshold,
                        List<LoveRetrievalCandidate> candidates, String decision,
                        List<LoveKnowledgeReference> references, String model, boolean streaming,
                        boolean degraded) {
        this(query, topK, similarityThreshold, candidates, decision, references, model, streaming, degraded, null);
    }
}
