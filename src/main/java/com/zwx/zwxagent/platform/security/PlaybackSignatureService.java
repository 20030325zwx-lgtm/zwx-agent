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
import java.util.UUID;

/**
 * 视频播放签名（V1-A）：&lt;video&gt; 标签无法携带 Authorization 头，
 * 这里签发自描述短时效令牌「expires.tenant.user.asset.hmac」，供
 * GET /ai/creative/assets/{id}/content?playback=… 免头播放。
 * JwtAuthFilter 按 GET + 精确 content 路径 + 素材匹配校验后仅为该请求装配身份，
 * 令牌不能当通用身份凭证使用；HMAC 覆盖租户/用户/素材/过期时间，防挪用与篡改。
 * 密钥复用 JWT 签名密钥，不新增密钥面。
 */
@Service
public class PlaybackSignatureService {

    private static final String DOMAIN = "asset-playback";
    private final byte[] secret;
    private final Duration ttl;

    public PlaybackSignatureService(@Value("${app.security.jwt-secret}") String secret,
                                    @Value("${creative.playback-ttl-seconds:3600}") long ttlSeconds) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = Duration.ofSeconds(Math.max(60, ttlSeconds));
    }

    public record PlaybackToken(String value, Instant expiresAt) {
    }

    /** 为本人素材签发播放令牌。 */
    public PlaybackToken issue(CurrentActor actor, UUID assetId) {
        Instant expiresAt = Instant.now().plus(ttl);
        long expiresEpoch = expiresAt.getEpochSecond();
        String payload = payload(actor.tenantId(), actor.userId(), assetId, expiresEpoch);
        String value = expiresEpoch + "." + actor.tenantId() + "." + actor.userId()
                + "." + assetId + "." + hmac(payload);
        return new PlaybackToken(value, expiresAt);
    }

    /**
     * 校验令牌：过期/篡改/素材不匹配返回 empty。expectedAssetId 来自请求路径，
     * 防止把某素材的签名挪用到其他素材。
     */
    public Optional<SignedAccess> verify(UUID expectedAssetId, String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        String[] parts = token.split("\\.");
        if (parts.length != 5) return Optional.empty();
        long expiresEpoch;
        UUID assetId;
        try {
            expiresEpoch = Long.parseLong(parts[0]);
            assetId = UUID.fromString(parts[3]);
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (expiresEpoch < Instant.now().getEpochSecond() || !assetId.equals(expectedAssetId)) {
            return Optional.empty();
        }
        String expected = hmac(payload(parts[1], parts[2], assetId, expiresEpoch));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                parts[4].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        return Optional.of(new SignedAccess(parts[2], parts[1]));
    }

    public record SignedAccess(String userId, String tenantId) {
    }

    private static String payload(String tenantId, String userId, UUID assetId, long expiresEpoch) {
        return DOMAIN + ":" + tenantId + ":" + userId + ":" + assetId + ":" + expiresEpoch;
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("播放签名计算失败", exception);
        }
    }
}
