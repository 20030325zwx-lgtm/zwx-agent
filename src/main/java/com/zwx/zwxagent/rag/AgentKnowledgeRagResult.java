package com.zwx.zwxagent.rag;

import java.util.List;

public record AgentKnowledgeRagResult(String context, List<LoveKnowledgeReference> references) {
}
