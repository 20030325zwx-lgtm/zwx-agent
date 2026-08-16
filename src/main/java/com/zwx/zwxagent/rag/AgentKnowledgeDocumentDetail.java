package com.zwx.zwxagent.rag;

import java.util.List;

public record AgentKnowledgeDocumentDetail(String id, String filename, String objectKey, String sourceContent,
                                           List<AgentKnowledgeChunk> chunks) {
}
