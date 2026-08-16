package com.zwx.zwxagent.rag;

/** A document represented in the love knowledge vector store. */
public record LoveKnowledgeDocumentSummary(
        String filename,
        String objectKey,
        int chunkCount,
        int sectionCount,
        boolean builtIn,
        boolean previewAvailable) {
}
