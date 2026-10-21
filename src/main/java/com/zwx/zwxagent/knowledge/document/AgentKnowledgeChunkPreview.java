package com.zwx.zwxagent.knowledge.document;

public record AgentKnowledgeChunkPreview(String chunkId, String documentId, String filename,
                                         Integer chunkIndex, String content) {
}
