package com.zwx.aster.eval.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * 鉴权链：/health 公开；/api/internal/** 只认 X-Aster-Internal-Token（常量时间比较，fail-closed）；
 * 其余 /api/ai/** 认主站 JWT，Actor 写入 request attribute。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = "aster.eval.actor";
    public static final String INTERNAL_HEADER = "X-Aster-Internal-Token";

    private final JwtVerifier jwtVerifier;
    private final String internalToken;

    public AuthFilter(JwtVerifier jwtVerifier, @Value("${app.internal.token:}") String internalToken) {
        this.jwtVerifier = jwtVerifier;
        this.internalToken = internalToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/health".equals(path) || path.startsWith("/eval/") || "/favicon.ico".equals(path)) {
            // 静态页面与健康检查公开；页面内的 /api 调用仍需 JWT
            filterChain.doFilter(request, response);
            return;
        }
        if (path.startsWith("/api/internal/")) {
            String provided = request.getHeader(INTERNAL_HEADER);
            if (internalToken.isBlank() || provided == null || !constantTimeEquals(provided, internalToken)) {
                unauthorized(response, "invalid internal token");
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            unauthorized(response, "missing bearer token");
            return;
        }
        Optional<JwtVerifier.Actor> actor = jwtVerifier.parse(authorization.substring(7).trim());
        if (actor.isEmpty()) {
            unauthorized(response, "invalid token");
            return;
        }
        request.setAttribute(ACTOR_ATTRIBUTE, actor.get());
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
