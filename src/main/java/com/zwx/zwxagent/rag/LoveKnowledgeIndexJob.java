package com.zwx.zwxagent.rag;

import java.time.Instant;

public record LoveKnowledgeIndexJob(String id, String status, int documentCount, int chunkCount,
                                    String errorMessage, Instant createdAt, Instant completedAt) {
}
