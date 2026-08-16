package com.zwx.zwxagent.app;

import com.zwx.zwxagent.rag.LoveRagTrace;

public record LoveVisionChatResult(LoveVisionAnalysis analysis, LoveRagTrace ragTrace, String systemPrompt) {
}
