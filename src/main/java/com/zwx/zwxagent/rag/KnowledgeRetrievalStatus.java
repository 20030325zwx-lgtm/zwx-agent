package com.zwx.zwxagent.rag;

/** Caller-visible reason for a private knowledge retrieval result. */
public enum KnowledgeRetrievalStatus {
    SUCCESS, NO_HIT, TIMEOUT, OVERLOADED, FAILED
}
