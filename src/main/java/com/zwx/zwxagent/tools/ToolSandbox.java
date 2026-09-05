package com.zwx.zwxagent.tools;

import com.zwx.zwxagent.constant.FileConstant;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ToolSandbox {

    public Path toolsRoot() {
        return Path.of(FileConstant.FILE_SAVE_DIR, "tools").toAbsolutePath().normalize();
    }

    public Path scopeDir(String scope) {
        String sanitized = scope == null || scope.isBlank() ? "shared" : scope.replaceAll("[^A-Za-z0-9_-]", "_");
        Path dir = toolsRoot().resolve(sanitized).normalize();
        if (!dir.startsWith(toolsRoot())) {
            throw new IllegalArgumentException("Invalid tool scope");
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create tool working directory", exception);
        }
        return dir;
    }

    public Path resolveWithin(Path workDir, String relativeName) {
        Path base = workDir.toAbsolutePath().normalize();
        if (relativeName == null || relativeName.isBlank()) {
            throw new IllegalArgumentException("File name is required");
        }
        Path resolved = base.resolve(relativeName).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("File name must stay inside the tool working directory");
        }
        try {
            Files.createDirectories(base);
            Path realBase = base.toRealPath();
            if (Files.exists(resolved)) {
                Path real = resolved.toRealPath();
                if (!real.startsWith(realBase)) {
                    throw new IllegalArgumentException("File name must stay inside the tool working directory");
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to verify tool working directory", exception);
        }
        return resolved;
    }
}
