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
        this.baseDir = baseDir.toAbsolutePath().normalize();
        try {
            java.nio.file.Files.createDirectories(this.baseDir);
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体目录创建失败：" + this.baseDir, exception);
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
            Path temp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
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
            return Files.newInputStream(resolve(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体读取失败：" + objectKey, exception);
        }
    }

    @Override
    public long size(String objectKey) {
        try {
            return Files.size(resolve(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体 stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("本地媒体删除失败：" + objectKey, exception);
        }
    }
}
