package com.zwx.aster.data.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/** 鉴权链：/health 公开；其余 /api/** 认主站 JWT，Actor 写入 request attribute。 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    public static final String ACTOR_ATTRIBUTE = "aster.data.actor";

    private final JwtVerifier jwtVerifier;

    public AuthFilter(JwtVerifier jwtVerifier) {
        this.jwtVerifier = jwtVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if ("/health".equals(path) || path.startsWith("/data/") || "/favicon.ico".equals(path)) {
            // 静态页面与健康检查公开；页面内的 /api 调用仍需 JWT
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

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
