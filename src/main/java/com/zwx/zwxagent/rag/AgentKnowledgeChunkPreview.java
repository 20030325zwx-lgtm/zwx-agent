package com.zwx.zwxagent.rag;

public record AgentKnowledgeChunkPreview(String chunkId, String documentId, String filename,
                                         Integer chunkIndex, String content) {
}
