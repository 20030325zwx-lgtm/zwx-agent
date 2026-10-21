package com.zwx.zwxagent.knowledge.storage;

import java.io.InputStream;

public record AgentImageContent(InputStream inputStream, String contentType) {
}
