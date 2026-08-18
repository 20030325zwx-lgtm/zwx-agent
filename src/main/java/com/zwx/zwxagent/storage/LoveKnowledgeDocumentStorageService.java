package com.zwx.zwxagent.storage;

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

    private final OssClientProvider ossClientProvider;
    private final String bucket;
    private final ResourcePatternResolver resourcePatternResolver;

    public LoveKnowledgeDocumentStorageService(OssClientProvider ossClientProvider,
                                               @Value("${app.oss.bucket}") String bucket,
                                               ResourcePatternResolver resourcePatternResolver) {
        this.ossClientProvider = ossClientProvider;
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
                    ossClientProvider.getClient().putObject(bucket, objectKey, inputStream, metadata);
                }
                uploads.add(new LoveKnowledgeDocumentUpload(filename, objectKey));
            }
            return uploads;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to upload bundled love knowledge documents", e);
        }
    }
}
