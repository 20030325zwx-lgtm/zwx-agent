package com.zwx.zwxagent.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Creates the OSS client only when an OSS-backed operation is actually used. */
@Component
public class OssClientProvider {

    private final String endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private volatile OSS client;

    public OssClientProvider(@Value("${app.oss.endpoint}") String endpoint,
                             @Value("${app.oss.access-key-id}") String accessKeyId,
                             @Value("${app.oss.access-key-secret}") String accessKeySecret) {
        this.endpoint = endpoint;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
    }

    public OSS getClient() {
        if (accessKeyId == null || accessKeyId.isBlank() || accessKeySecret == null || accessKeySecret.isBlank()) {
            throw new IllegalStateException("OSS credentials are required for image or document operations");
        }
        OSS current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
                    client = current;
                }
            }
        }
        return current;
    }
}
