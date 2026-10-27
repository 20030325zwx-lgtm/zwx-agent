package com.zwx.zwxagent.platform.workspace;

import java.util.List;

public record WorkspaceDirectoryView(
        String mode,
        String currentPath,
        String displayPath,
        String parentPath,
        boolean selectable,
        List<DirectoryEntry> directories) {

    public record DirectoryEntry(String name, String path) {
    }
}
