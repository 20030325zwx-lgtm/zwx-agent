package com.zwx.zwxagent.platform.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 存储路由：写与列举走主存储（新的 MinIO），读取在主存储缺失时回退备用存储（历史 OSS 对象）。
 * 迁移期用它保证「切换瞬间旧对象仍可读」，切换完成后可以只保留主存储。
 */
public class ObjectStorageRouter implements ObjectStorage {

    private final ObjectStorage primary;
    private final ObjectStorage fallback;

    public ObjectStorageRouter(ObjectStorage primary, ObjectStorage fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String backend() {
        return primary.backend();
    }

    @Override
    public String bucket() {
        return primary.bucket();
    }

    @Override
    public void put(String objectKey, String contentType, InputStream in, long byteSize) {
        primary.put(objectKey, contentType, in, byteSize);
    }

    @Override
    public void putBytes(String objectKey, String contentType, byte[] bytes) {
        primary.putBytes(objectKey, contentType, bytes);
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return primary.open(objectKey);
        } catch (ObjectNotFoundException exception) {
            if (fallback == null) throw exception;
            return fallback.open(objectKey);
        }
    }

    @Override
    public long size(String objectKey) {
        try {
            return primary.size(objectKey);
        } catch (ObjectNotFoundException exception) {
            if (fallback == null) throw exception;
            return fallback.size(objectKey);
        }
    }

    @Override
    public String contentType(String objectKey) {
        try {
            return primary.contentType(objectKey);
        } catch (ObjectNotFoundException exception) {
            if (fallback == null) throw exception;
            return fallback.contentType(objectKey);
        }
    }

    @Override
    public void delete(String objectKey) {
        // 迁移期两边都删，避免删除后旧对象还能从备用后端读到
        boolean deleted = false;
        try {
            primary.delete(objectKey);
            deleted = true;
        } catch (ObjectNotFoundException ignored) {
            // 主存储没有该对象
        }
        if (fallback != null) {
            try {
                fallback.delete(objectKey);
            } catch (RuntimeException fallbackFailure) {
                if (!deleted) throw fallbackFailure;
            }
        }
    }

    @Override
    public boolean exists(String objectKey) {
        if (primary.exists(objectKey)) return true;
        return fallback != null && fallback.exists(objectKey);
    }

    @Override
    public List<String> listKeys(String prefix) {
        Set<String> keys = new LinkedHashSet<>(primary.listKeys(prefix));
        if (fallback != null) keys.addAll(fallback.listKeys(prefix));
        return new ArrayList<>(keys);
    }

    @Override
    public Optional<String> presignedGetUrl(String objectKey, Duration ttl, String downloadName) {
        Optional<String> url = primary.presignedGetUrl(objectKey, ttl, downloadName);
        if (url.isPresent()) return url;
        return fallback == null ? Optional.empty() : fallback.presignedGetUrl(objectKey, ttl, downloadName);
    }

    @Override
    public boolean ping() {
        return primary.ping() || (fallback != null && fallback.ping());
    }

    public ObjectStorage primary() {
        return primary;
    }

    public ObjectStorage fallback() {
        return fallback;
    }
}
