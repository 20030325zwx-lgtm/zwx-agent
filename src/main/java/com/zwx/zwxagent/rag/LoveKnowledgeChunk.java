package com.zwx.zwxagent.rag;

/** One persisted vector-store chunk with the source metadata needed for auditing. */
public record LoveKnowledgeChunk(String id, int chunkIndex, Integer section, String content) {
}
