package com.zwx.zwxagent.rag;

import java.util.List;

public record LoveRagTrace(String query, int topK, double similarityThreshold,
                           List<LoveRetrievalCandidate> candidates, String decision,
                           List<LoveKnowledgeReference> references, String model, boolean streaming,
                           boolean degraded) {
    public LoveRagTrace {
        if (degraded && decision == null) {
            decision = "知识库检索不可用，模型仅使用系统提示词与会话上下文回答。";
        }
    }
}
