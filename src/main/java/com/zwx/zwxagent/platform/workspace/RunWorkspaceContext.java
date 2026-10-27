package com.zwx.zwxagent.platform.workspace;

import java.nio.file.Path;

/** 一次 Run 固化的项目源码 checkout。 */
public record RunWorkspaceContext(String runId, String projectId, String projectName, Path checkoutRoot,
                                  Mode mode, String branchName, String baseHead) {
    public enum Mode { DIRECT, WORKTREE }

    public boolean direct() {
        return mode == Mode.DIRECT;
    }
}
