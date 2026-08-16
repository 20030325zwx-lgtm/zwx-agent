package com.yupi.yuaiagent.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class LoveImageStorageService {

    private static final long MAX_IMAGE_SIZE = 10L * 1024 * 1024;
    private static final List<String> ALLOWED_TYPES = List.of("image/jpeg", "image/png", "image/gif");

    private final OSS ossClient;
    private final String bucket;

    public LoveImageStorageService(@Value("${app.oss.endpoint}") String endpoint,
                                   @Value("${app.oss.access-key-id}") String accessKeyId,
                                   @Value("${app.oss.access-key-secret}") String accessKeySecret,
                                   @Value("${app.oss.bucket}") String bucket) {
        if (accessKeyId.isBlank() || accessKeySecret.isBlank()) {
            throw new IllegalStateException("OSS credentials are required in application-local.yml or environment variables");
        }
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        this.bucket = bucket;
    }

    public LoveImageUpload upload(String conversationId, MultipartFile file) {
        validateConversationId(conversationId);
        validateImage(file);
        String contentType = file.getContentType().toLowerCase(Locale.ROOT);
        String objectKey = "love/" + conversationId + "/" + UUID.randomUUID() + extension(contentType);
        try (InputStream inputStream = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(contentType);
            ossClient.putObject(bucket, objectKey, inputStream, metadata);
            return new LoveImageUpload(objectKey);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to store image", e);
        }
    }

    public String presignedReadUrl(String conversationId, String objectKey) {
        validateObjectKey(conversationId, objectKey);
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
            request.setExpiration(new Date(System.currentTimeMillis() + 15 * 60 * 1000));
            return ossClient.generatePresignedUrl(request).toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to create image read URL", e);
        }
    }

    public LoveImageContent getImage(String conversationId, String objectKey) {
        validateObjectKey(conversationId, objectKey);
        try {
            var object = ossClient.getObject(bucket, objectKey);
            return new LoveImageContent(object.getObjectContent(), object.getObjectMetadata().getContentType());
        } catch (Exception e) {
            throw new IllegalArgumentException("Image not found", e);
        }
    }

    public void validateObjectKey(String conversationId, String objectKey) {
        validateConversationId(conversationId);
        if (objectKey == null || !objectKey.startsWith("love/" + conversationId + "/") || objectKey.contains("..")) {
            throw new IllegalArgumentException("Invalid image reference");
        }
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("Image must be between 1 byte and 10 MB");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Only JPEG, PNG, and GIF images are supported");
        }
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(8);
            boolean jpeg = header.length >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF;
            boolean png = header.length == 8 && header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47;
            boolean gif = header.length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F';
            if (!jpeg && !png && !gif) {
                throw new IllegalArgumentException("Invalid image content");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read image", e);
        }
    }

    private void validateConversationId(String conversationId) {
        try {
            UUID.fromString(conversationId);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid conversation id");
        }
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> ".gif";
        };
    }
}
