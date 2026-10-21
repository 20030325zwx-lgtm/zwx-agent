package com.zwx.zwxagent.knowledge.document;

import java.util.List;

public record AgentKnowledgeDocumentDetail(String id, String filename, String objectKey, String sourceContent,
                                           List<AgentKnowledgeChunk> chunks) {
}
