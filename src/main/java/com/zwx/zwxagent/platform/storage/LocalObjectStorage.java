package com.zwx.zwxagent.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 本地目录存储（桌面应用/无对象存储场景）：临时文件写入后原子 rename；
 * 读取前做规范化与符号链接越界校验。
 */
public class LocalObjectStorage implements ObjectStorage {

    private final Path baseDir;

    public LocalObjectStorage(Path baseDir) {
        Path normalized = baseDir.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
            // 以真实路径作为边界，避免基础目录本身是符号链接时判断失效
            this.baseDir = normalized.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("本地对象目录创建失败：" + normalized, exception);
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
    public String bucket() {
        return baseDir.toString();
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
            throw new IllegalStateException("本地写入失败：" + objectKey, exception);
        }
    }

    @Override
    public void putBytes(String objectKey, String contentType, byte[] bytes) {
        put(objectKey, contentType, new java.io.ByteArrayInputStream(bytes), bytes.length);
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return Files.newInputStream(resolveReal(objectKey));
        } catch (IOException exception) {
            if (!Files.exists(resolve(objectKey))) throw new ObjectNotFoundException(objectKey);
            throw new IllegalStateException("本地读取失败：" + objectKey, exception);
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
            if (!Files.exists(resolve(objectKey))) throw new ObjectNotFoundException(objectKey);
            throw new IllegalStateException("本地 stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public String contentType(String objectKey) {
        try {
            String probed = Files.probeContentType(resolveReal(objectKey));
            return probed == null || probed.isBlank() ? "application/octet-stream" : probed;
        } catch (IOException exception) {
            throw new ObjectNotFoundException(objectKey);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolveReal(objectKey));
        } catch (IOException exception) {
            throw new IllegalStateException("本地删除失败：" + objectKey, exception);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        return Files.isRegularFile(resolve(objectKey));
    }

    @Override
    public List<String> listKeys(String prefix) {
        Path start = prefix == null || prefix.isBlank() ? baseDir : resolve(normalizePrefix(prefix));
        if (!Files.isDirectory(start)) return List.of();
        List<String> keys = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(start)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> baseDir.relativize(path).toString().replace(java.io.File.separatorChar, '/'))
                    .filter(key -> prefix == null || prefix.isBlank() || key.startsWith(prefix))
                    .forEach(keys::add);
        } catch (IOException exception) {
            throw new IllegalStateException("本地列举失败：" + prefix, exception);
        }
        return keys;
    }

    /** 前缀可能是目录（以 / 结尾）；本地按目录定位，去掉尾部斜杠再解析。 */
    private static String normalizePrefix(String prefix) {
        return prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
    }

    @Override
    public Optional<String> presignedGetUrl(String objectKey, java.time.Duration ttl, String downloadName) {
        return Optional.empty();
    }

    @Override
    public boolean ping() {
        return Files.isDirectory(baseDir);
    }
}
