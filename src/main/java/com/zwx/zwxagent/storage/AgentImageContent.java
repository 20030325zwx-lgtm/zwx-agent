package com.zwx.zwxagent.storage;

import java.io.InputStream;

public record AgentImageContent(InputStream inputStream, String contentType) {
}
