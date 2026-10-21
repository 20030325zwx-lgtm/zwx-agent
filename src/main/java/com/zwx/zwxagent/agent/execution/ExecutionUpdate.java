package com.zwx.zwxagent.agent.execution;

import java.util.Map;

public record ExecutionUpdate(String phase, String summary, Map<String, Object> detail) {
}
