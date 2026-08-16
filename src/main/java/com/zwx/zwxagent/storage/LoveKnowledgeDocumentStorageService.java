package com.zwx.zwxagent.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Service
public class LoveKnowledgeDocumentStorageService {

    private final OSS ossClient;
    private final String bucket;
    private final ResourcePatternResolver resourcePatternResolver;

    public LoveKnowledgeDocumentStorageService(@Value("${app.oss.endpoint}") String endpoint,
                                               @Value("${app.oss.access-key-id}") String accessKeyId,
                                               @Value("${app.oss.access-key-secret}") String accessKeySecret,
                                               @Value("${app.oss.bucket}") String bucket,
                                               ResourcePatternResolver resourcePatternResolver) {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            throw new IllegalStateException("OSS credentials are required in application-local.yml or environment variables");
        }
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        this.bucket = bucket;
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<LoveKnowledgeDocumentUpload> uploadBundledDocuments() {
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            List<LoveKnowledgeDocumentUpload> uploads = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename == null) {
                    continue;
                }
                String objectKey = "knowledge/love/" + filename;
                ObjectMetadata metadata = new ObjectMetadata();
                metadata.setContentLength(resource.contentLength());
                metadata.setContentType("text/markdown; charset=UTF-8");
                try (InputStream inputStream = resource.getInputStream()) {
                    ossClient.putObject(bucket, objectKey, inputStream, metadata);
                }
                uploads.add(new LoveKnowledgeDocumentUpload(filename, objectKey));
            }
            return uploads;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upload bundled love knowledge documents", e);
        }
    }
}
