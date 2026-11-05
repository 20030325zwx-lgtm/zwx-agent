package com.zwx.zwxagent.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 工作流产物下载签名（2026-11-05）：对象存储改为内网 MinIO 后，浏览器不能再直连预签名地址，
 * 产物改由本服务流式下载。&lt;img&gt; / &lt;a&gt; 带不了 Authorization 头，这里签发自描述短时效令牌
 * 「expires.tenant.user.hmac(key)」，只对 GET /ai/workflows/artifacts/content + 匹配的对象键放行。
 * 密钥复用 JWT 签名密钥，不新增密钥面。
 */
@Service
public class ArtifactSignatureService {

    private static final String DOMAIN = "artifact-download";
    private final byte[] secret;
    private final Duration ttl;

    public ArtifactSignatureService(@Value("${app.security.jwt-secret}") String secret,
                                    @Value("${app.workflow.artifact-ttl-seconds:7200}") long ttlSeconds) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    /** 为某次运行的产物签发下载令牌（绑定对象键，不能挪用到别的产物）。 */
    public String issue(String tenantId, String userId, String objectKey) {
        long expiresEpoch = Instant.now().plus(ttl).getEpochSecond();
        return expiresEpoch + "." + tenantId + "." + userId + "." + hmac(payload(tenantId, userId, objectKey, expiresEpoch));
    }

    /** 校验令牌：过期、篡改或对象键不匹配都视为无效。 */
    public Optional<SignedAccess> verify(String objectKey, String token) {
        if (token == null || token.isBlank() || objectKey == null || objectKey.isBlank()) return Optional.empty();
        String[] parts = token.split("\\.");
        if (parts.length != 4) return Optional.empty();
        long expiresEpoch;
        try {
            expiresEpoch = Long.parseLong(parts[0]);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (expiresEpoch < Instant.now().getEpochSecond()) return Optional.empty();
        String expected = hmac(payload(parts[1], parts[2], objectKey, expiresEpoch));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[3].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(new SignedAccess(parts[2], parts[1]));
    }

    public record SignedAccess(String userId, String tenantId) {
    }

    private static String payload(String tenantId, String userId, String objectKey, long expiresEpoch) {
        return DOMAIN + ":" + tenantId + ":" + userId + ":" + objectKey + ":" + expiresEpoch;
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("产物签名计算失败", exception);
        }
    }
}
