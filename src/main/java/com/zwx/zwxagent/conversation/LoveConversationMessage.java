package com.zwx.zwxagent.conversation;

import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import com.zwx.zwxagent.rag.LoveRagTrace;
import com.zwx.zwxagent.app.LoveVisionAnalysis;

import java.time.Instant;
import java.util.List;

public record LoveConversationMessage(String role, String content, List<String> imageObjectKeys,
                                      List<LoveKnowledgeReference> knowledgeReferences,
                                      LoveRagTrace ragTrace, LoveVisionAnalysis visionAnalysis, Instant createdAt) {
}
