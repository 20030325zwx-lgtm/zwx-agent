package com.zwx.zwxagent.rag;

/** Retrieval trace plus the bounded source excerpts that may be sent to a model. */
public record LoveRagResult(LoveRagTrace trace, String context) {
}
