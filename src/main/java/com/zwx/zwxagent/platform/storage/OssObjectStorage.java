package com.zwx.zwxagent.platform.storage;

import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsRequest;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.ResponseHeaderOverrides;
import com.zwx.zwxagent.knowledge.storage.OssClientProvider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * 阿里云 OSS 实现（历史对象读取与回滚用）。桶保持私有，读地址靠短时效预签名。
 * 迁移到 MinIO 后本实现默认只作为只读兜底，可由配置关闭。
 */
public class OssObjectStorage implements ObjectStorage {

    private final OssClientProvider ossClientProvider;
    private final String bucket;

    public OssObjectStorage(OssClientProvider ossClientProvider, String bucket) {
        this.ossClientProvider = ossClientProvider;
        this.bucket = bucket;
    }

    @Override
    public String backend() {
        return "OSS";
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public void put(String objectKey, String contentType, InputStream in, long byteSize) {
        var metadata = new ObjectMetadata();
        metadata.setContentLength(byteSize);
        metadata.setContentType(contentType);
        ossClientProvider.getClient().putObject(bucket, objectKey, in, metadata);
    }

    @Override
    public void putBytes(String objectKey, String contentType, byte[] bytes) {
        var metadata = new ObjectMetadata();
        metadata.setContentLength((long) bytes.length);
        metadata.setContentType(contentType);
        ossClientProvider.getClient().putObject(bucket, objectKey, new ByteArrayInputStream(bytes), metadata);
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            return ossClientProvider.getClient().getObject(bucket, objectKey).getObjectContent();
        } catch (com.aliyun.oss.OSSException exception) {
            if ("NoSuchKey".equals(exception.getErrorCode())) throw new ObjectNotFoundException(objectKey);
            throw new IllegalStateException("OSS 读取失败：" + objectKey, exception);
        }
    }

    @Override
    public long size(String objectKey) {
        try {
            return ossClientProvider.getClient().getObjectMetadata(bucket, objectKey).getContentLength();
        } catch (com.aliyun.oss.OSSException exception) {
            if ("NoSuchKey".equals(exception.getErrorCode())) throw new ObjectNotFoundException(objectKey);
            throw new IllegalStateException("OSS stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public String contentType(String objectKey) {
        try {
            String value = ossClientProvider.getClient().getObjectMetadata(bucket, objectKey).getContentType();
            return value == null || value.isBlank() ? "application/octet-stream" : value;
        } catch (com.aliyun.oss.OSSException exception) {
            if ("NoSuchKey".equals(exception.getErrorCode())) throw new ObjectNotFoundException(objectKey);
            throw new IllegalStateException("OSS stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        ossClientProvider.getClient().deleteObject(bucket, objectKey);
    }

    @Override
    public boolean exists(String objectKey) {
        return ossClientProvider.getClient().doesObjectExist(bucket, objectKey);
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String marker = null;
        do {
            var request = new ListObjectsRequest(bucket)
                    .withPrefix(prefix == null ? "" : prefix)
                    .withMaxKeys(1000);
            if (marker != null) request.setMarker(marker);
            var listing = ossClientProvider.getClient().listObjects(request);
            listing.getObjectSummaries().forEach(summary -> keys.add(summary.getKey()));
            marker = listing.isTruncated() ? listing.getNextMarker() : null;
        } while (marker != null && !marker.isEmpty());
        return keys;
    }

    @Override
    public Optional<String> presignedGetUrl(String objectKey, Duration ttl, String downloadName) {
        long seconds = Math.max(60, ttl == null ? 900 : ttl.toSeconds());
        var request = new GeneratePresignedUrlRequest(bucket, objectKey,
                com.aliyun.oss.HttpMethod.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + seconds * 1000));
        if (downloadName != null && !downloadName.isBlank()) {
            var overrides = new ResponseHeaderOverrides();
            overrides.setContentDisposition("attachment; filename*=UTF-8''"
                    + URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20"));
            request.setResponseHeaders(overrides);
        }
        URL url = ossClientProvider.getClient().generatePresignedUrl(request);
        return Optional.of(url.toString());
    }

    @Override
    public boolean ping() {
        try {
            return ossClientProvider.getClient().doesBucketExist(bucket);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
