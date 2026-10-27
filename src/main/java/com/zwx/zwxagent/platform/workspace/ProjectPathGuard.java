package com.zwx.zwxagent.platform.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** 项目源码工具共用的路径边界与敏感文件守卫。 */
public final class ProjectPathGuard {

    private final Path root;

    public ProjectPathGuard(Path root) {
        try {
            this.root = root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("项目目录不存在或无法访问", exception);
        }
    }

    public Path root() {
        return root;
    }

    public Path resolveExisting(String relativePath) {
        String value = normalizeRelative(relativePath);
        Path candidate = value.isEmpty() ? root : root.resolve(value).normalize();
        rejectSymlinkSegments(candidate);
        try {
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) throw new IllegalArgumentException("路径越出了项目目录");
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("项目文件不存在或无法访问", exception);
        }
    }

    public String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    public boolean isSensitive(Path path) {
        Path relative = root.relativize(path.normalize());
        for (Path segment : relative) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (name.equals(".git") || name.equals(".ssh") || name.equals(".aws")
                    || name.equals(".gnupg") || name.equals(".env") || name.startsWith(".env.")
                    || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
                    || name.endsWith(".pfx") || name.equals("credentials") || name.equals("credentials.json")) {
                return true;
            }
        }
        return false;
    }

    private String normalizeRelative(String relativePath) {
        String value = relativePath == null ? "" : relativePath.trim().replace('\\', '/');
        if (value.equals(".")) return "";
        Path parsed = Path.of(value.isEmpty() ? "." : value);
        if (parsed.isAbsolute() || value.startsWith("~")) throw new IllegalArgumentException("必须使用项目内相对路径");
        for (Path segment : parsed) {
            if ("..".equals(segment.toString())) throw new IllegalArgumentException("路径不能包含 ..");
        }
        return value;
    }

    private void rejectSymlinkSegments(Path candidate) {
        Path current = root;
        Path relative;
        try {
            relative = root.relativize(candidate);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("路径越出了项目目录", exception);
        }
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("项目路径不能经过符号链接");
        }
    }
}
