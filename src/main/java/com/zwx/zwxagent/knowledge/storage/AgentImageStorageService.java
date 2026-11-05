package com.zwx.zwxagent.knowledge.storage;

import com.zwx.zwxagent.platform.storage.ObjectStorage;
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

    private final ObjectStorage storage;
    private final long maxBytes;
    private final long signedUrlSeconds;

    public AgentImageStorageService(ObjectStorage storage,
                                    @Value("${app.image.max-bytes:10485760}") long maxBytes,
                                    @Value("${app.image.signed-url-seconds:900}") long signedUrlSeconds) {
        this.storage = storage;
        this.maxBytes = maxBytes;
        this.signedUrlSeconds = signedUrlSeconds;
    }

    public AgentImageUpload upload(String agentKey, String conversationId, MultipartFile file) {
        validateScope(agentKey, conversationId);
        validateImage(file);
        String contentType = file.getContentType().toLowerCase(Locale.ROOT);
        String objectKey = agentKey + "/" + conversationId + "/" + UUID.randomUUID() + extension(contentType);
        try (InputStream inputStream = file.getInputStream()) {
            storage.put(objectKey, contentType, inputStream, file.getSize());
            return new AgentImageUpload(objectKey);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to store image", exception);
        }
    }

    /** 外部可达的读地址：对象存储没有公网入口时返回空，由调用方改用字节直传。 */
    public java.util.Optional<String> presignedReadUrl(String agentKey, String conversationId, String objectKey) {
        validateObjectKey(agentKey, conversationId, objectKey);
        try {
            return storage.presignedGetUrl(objectKey, java.time.Duration.ofSeconds(signedUrlSeconds), null);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create image read URL", exception);
        }
    }

    /** 读取图片字节（图片理解改走视觉模型直传，不再依赖存储公网地址）。 */
    public byte[] readBytes(String agentKey, String conversationId, String objectKey) {
        validateObjectKey(agentKey, conversationId, objectKey);
        try (var in = storage.open(objectKey)) {
            return in.readAllBytes();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Unable to read image", exception);
        }
    }

    public AgentImageContent getImage(String agentKey, String conversationId, String objectKey) {
        validateObjectKey(agentKey, conversationId, objectKey);
        try {
            return new AgentImageContent(storage.open(objectKey), mimeTypeOf(objectKey));
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

    /** 对象键按上传时生成的扩展名推回 MIME，避免额外一次元数据查询。 */
    public static String mimeTypeOf(String objectKey) {
        String lower = objectKey == null ? "" : objectKey.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
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
