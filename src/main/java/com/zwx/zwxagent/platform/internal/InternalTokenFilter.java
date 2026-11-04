package com.zwx.zwxagent.platform.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 服务间内部接口鉴权：/internal/** 只认 X-Aster-Internal-Token（常量时间比较）。
 * 未配置令牌时 fail-closed（一律拒绝），避免空密钥变成公开端点。
 * 由 InternalSecurityConfig 显式注册，不加 @Component 以免重复注册。
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    public static final String INTERNAL_HEADER = "X-Aster-Internal-Token";

    private final String internalToken;

    public InternalTokenFilter(String internalToken) {
        this.internalToken = internalToken;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(INTERNAL_HEADER);
        if (internalToken.isBlank() || provided == null || !constantTimeEquals(provided, internalToken)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"invalid internal token\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
