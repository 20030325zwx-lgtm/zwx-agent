package com.zwx.zwxagent.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(@Value("${app.security.jwt-secret}") String secret,
                      @Value("${app.security.jwt-ttl-hours:72}") long ttlHours) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = Duration.ofHours(ttlHours);
    }

    public String issue(CurrentActor actor) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(actor.username())
                .claim("uid", actor.userId())
                .claim("tenant", actor.tenantId())
                .claim("role", actor.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<CurrentActor> parse(String token) {
        try {
            Claims claims = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            String userId = claims.get("uid", String.class);
            String tenantId = claims.get("tenant", String.class);
            String username = claims.getSubject();
            if (userId == null || tenantId == null || username == null) return Optional.empty();
            return Optional.of(new CurrentActor(userId, tenantId, username, Role.from(claims.get("role", String.class))));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
