package com.zwx.zwxagent.rag;

import java.util.List;

/** Source preview and the exact chunks currently persisted for one document. */
public record LoveKnowledgeDocumentDetail(
        String filename,
        String objectKey,
        String sourceContent,
        List<LoveKnowledgeChunk> chunks) {
}
