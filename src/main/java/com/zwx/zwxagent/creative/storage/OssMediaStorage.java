package com.zwx.zwxagent.creative.storage;

import com.zwx.zwxagent.knowledge.storage.OssClientProvider;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;

/** 云端 OSS 存储：复用知识库的 OssClientProvider（应用内唯一凭据来源）。桶保持私有（设计 §11）。 */
@Component
public class OssMediaStorage implements MediaStorage {

    private final OssClientProvider ossClientProvider;
    private final String bucket;

    public OssMediaStorage(OssClientProvider ossClientProvider,
                           @org.springframework.beans.factory.annotation.Value("${app.oss.bucket}") String bucket) {
        this.ossClientProvider = ossClientProvider;
        this.bucket = bucket;
    }

    @Override
    public String backend() {
        return "OSS";
    }

    @Override
    public void put(String objectKey, String contentType, InputStream in, long byteSize) {
        var metadata = new com.aliyun.oss.model.ObjectMetadata();
        metadata.setContentLength(byteSize);
        metadata.setContentType(contentType);
        ossClientProvider.getClient().putObject(bucket, objectKey, in, metadata);
    }

    @Override
    public InputStream open(String objectKey) {
        return ossClientProvider.getClient().getObject(bucket, objectKey).getObjectContent();
    }

    @Override
    public long size(String objectKey) {
        URL url = ossClientProvider.getClient().generatePresignedUrl(bucket, objectKey, java.util.Date.from(java.time.Instant.now().plusSeconds(60)));
        try {
            var connection = url.openConnection();
            return connection.getContentLengthLong();
        } catch (Exception exception) {
            throw new IllegalStateException("OSS stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        ossClientProvider.getClient().deleteObject(bucket, objectKey);
    }

    /** 供“按字节上传”的便捷入口（服务层已读入字节的场景）。 */
    public void putBytes(String objectKey, String contentType, byte[] bytes) {
        var metadata = new com.aliyun.oss.model.ObjectMetadata();
        metadata.setContentLength((long) bytes.length);
        metadata.setContentType(contentType);
        ossClientProvider.getClient().putObject(bucket, objectKey, new ByteArrayInputStream(bytes), metadata);
    }
}
