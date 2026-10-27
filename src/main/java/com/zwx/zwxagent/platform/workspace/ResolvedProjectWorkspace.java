package com.zwx.zwxagent.platform.workspace;

import java.nio.file.Path;

/** 服务端内部使用的项目真实路径，不直接暴露给 Web API 或模型。 */
public record ResolvedProjectWorkspace(String projectId, String name, Path root) {
}
