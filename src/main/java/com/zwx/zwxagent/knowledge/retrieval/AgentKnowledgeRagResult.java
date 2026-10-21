package com.zwx.zwxagent.knowledge.retrieval;

import java.util.List;

public record AgentKnowledgeRagResult(String context, List<LoveKnowledgeReference> references,
                                      KnowledgeRetrievalOutcome outcome, RagRetrievalTrace trace) {

    public static final AgentKnowledgeRagResult EMPTY = new AgentKnowledgeRagResult("", List.of(), KnowledgeRetrievalOutcome.noHit());

    public AgentKnowledgeRagResult(String context, List<LoveKnowledgeReference> references) {
        this(context, references, context == null || context.isBlank()
                ? KnowledgeRetrievalOutcome.noHit() : KnowledgeRetrievalOutcome.success(0, 0, false), null);
    }

    public AgentKnowledgeRagResult(String context, List<LoveKnowledgeReference> references, KnowledgeRetrievalOutcome outcome) {
        this(context, references, outcome, null);
    }
}
