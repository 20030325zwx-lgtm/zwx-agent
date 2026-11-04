package com.zwx.aster.data.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** 与主站同源 JWT 验签：claims sub/uid/tenant/role，无需访问主库。 */
@Component
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(@Value("${app.jwt.secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Actor> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            String userId = claims.get("uid", String.class);
            String tenantId = claims.get("tenant", String.class);
            String username = claims.getSubject();
            if (userId == null || tenantId == null || username == null) return Optional.empty();
            String role = claims.get("role", String.class);
            return Optional.of(new Actor(userId, tenantId, username, role == null ? "USER" : role));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public record Actor(String userId, String tenantId, String username, String role) {

        public boolean isAdmin() {
            return "ADMIN".equalsIgnoreCase(role);
        }

        public void requireAdmin() {
            if (!isAdmin()) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "需要管理员权限");
            }
        }
    }
}
