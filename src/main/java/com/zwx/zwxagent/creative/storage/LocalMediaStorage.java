package com.zwx.zwxagent.creative.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 本地媒体存储（桌面/无 OSS 场景）：写入临时文件后原子 rename；
 * key 由服务端生成（UUID 路径），读取前仍做规范化与越界校验（A15）。
 */
public class LocalMediaStorage implements MediaStorage {

    private final Path baseDir;

    public LocalMediaStorage(Path baseDir) {
        Path normalized = baseDir.toAbsolutePath().normalize();
        try {
            java.nio.file.Files.createDirectories(normalized);
            // 以真实路径作为边界，避免基础目录本身是符号链接时判断失效
            this.baseDir = normalized.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体目录创建失败：" + normalized, exception);
        }
    }

    /** 解析并校验 key 不会逃出基础目录（拒绝 ..、绝对路径与符号链接逃逸）。 */
    public Path resolve(String objectKey) {
        Path target = baseDir.resolve(objectKey).normalize();
        if (!target.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法的对象键：" + objectKey);
        }
        return target;
    }

    @Override
    public String backend() {
        return "LOCAL";
    }

    @Override
    public void put(String objectKey, String contentType, InputStream in, long byteSize) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            // 父目录必须真实位于基础目录内：拦截通过符号链接目录写到外部
            Path realParent = target.getParent().toRealPath();
            if (!realParent.startsWith(baseDir)) {
                throw new IllegalArgumentException("非法的对象键：" + objectKey);
            }
            target = realParent.resolve(target.getFileName());
            Path temp = Files.createTempFile(realParent, "upload-", ".tmp");
            try (in) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体写入失败：" + objectKey, exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            Path real = resolveReal(objectKey);
            return Files.newInputStream(real);
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体读取失败：" + objectKey, exception);
        }
    }

    /** 已存在对象的真实路径校验：拒绝文件本身或任一级父目录的符号链接逃逸。 */
    private Path resolveReal(String objectKey) throws IOException {
        Path real = resolve(objectKey).toRealPath();
        if (!real.startsWith(baseDir)) {
            throw new IllegalArgumentException("非法的对象键：" + objectKey);
        }
        return real;
    }

    @Override
    public long size(String objectKey) {
        try {
            return Files.size(resolveReal(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体 stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolveReal(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体删除失败：" + objectKey, exception);
        }
    }
}
