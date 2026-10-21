package com.zwx.zwxagent.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 对话图片的通用对象存储：对象 key 按 agentKey/conversationId 隔离。 */
@Service
public class AgentImageStorageService {

    private static final List<String> ALLOWED_TYPES = List.of(
            "image/jpeg", "image/png", "image/gif", "image/webp");

    private final OssClientProvider ossClientProvider;
    private final String bucket;
    private final long maxBytes;
    private final long signedUrlSeconds;

    public AgentImageStorageService(OssClientProvider ossClientProvider,
                                    @Value("${app.oss.bucket}") String bucket,
                                    @Value("${app.image.max-bytes:10485760}") long maxBytes,
                                    @Value("${app.image.signed-url-seconds:900}") long signedUrlSeconds) {
        this.ossClientProvider = ossClientProvider;
        this.bucket = bucket;
        this.maxBytes = maxBytes;
        this.signedUrlSeconds = signedUrlSeconds;
    }

    public AgentImageUpload upload(String agentKey, String conversationId, MultipartFile file) {
        validateScope(agentKey, conversationId);
        validateImage(file);
        String contentType = file.getContentType().toLowerCase(Locale.ROOT);
        String objectKey = agentKey + "/" + conversationId + "/" + UUID.randomUUID() + extension(contentType);
        try (InputStream inputStream = file.getInputStream()) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(file.getSize());
            metadata.setContentType(contentType);
            ossClientProvider.getClient().putObject(bucket, objectKey, inputStream, metadata);
            return new AgentImageUpload(objectKey);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to store image", exception);
        }
    }

    public String presignedReadUrl(String agentKey, String conversationId, String objectKey) {
        validateObjectKey(agentKey, conversationId, objectKey);
        try {
            GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(bucket, objectKey, HttpMethod.GET);
            request.setExpiration(new Date(System.currentTimeMillis() + signedUrlSeconds * 1000));
            return ossClientProvider.getClient().generatePresignedUrl(request).toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create image read URL", exception);
        }
    }

    public AgentImageContent getImage(String agentKey, String conversationId, String objectKey) {
        validateObjectKey(agentKey, conversationId, objectKey);
        try {
            var object = ossClientProvider.getClient().getObject(bucket, objectKey);
            return new AgentImageContent(object.getObjectContent(), object.getObjectMetadata().getContentType());
        } catch (Exception exception) {
            throw new IllegalArgumentException("Image not found", exception);
        }
    }

    public void validateObjectKey(String agentKey, String conversationId, String objectKey) {
        validateScope(agentKey, conversationId);
        if (objectKey == null || !objectKey.startsWith(agentKey + "/" + conversationId + "/") || objectKey.contains("..")) {
            throw new IllegalArgumentException("Invalid image reference");
        }
    }

    private void validateScope(String agentKey, String conversationId) {
        if (agentKey == null || !agentKey.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("Invalid agent key");
        }
        try {
            UUID.fromString(conversationId);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid conversation id");
        }
    }

    private void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > maxBytes) {
            throw new IllegalArgumentException("Image size must be between 1 byte and " + maxBytes + " bytes");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Only JPEG, PNG, GIF and WebP images are supported");
        }
        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(12);
            boolean jpeg = header.length >= 3 && header[0] == (byte) 0xFF && header[1] == (byte) 0xD8 && header[2] == (byte) 0xFF;
            boolean png = header.length >= 8 && header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47;
            boolean gif = header.length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F';
            boolean webp = header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
            if (!jpeg && !png && !gif && !webp) {
                throw new IllegalArgumentException("Invalid image content");
            }
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("Unable to read image", exception);
        }
    }

    private String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".gif";
        };
    }
}
