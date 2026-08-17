package com.zwx.zwxagent.execution;

import java.util.Map;

public record ExecutionUpdate(String phase, String summary, Map<String, Object> detail) {
}
