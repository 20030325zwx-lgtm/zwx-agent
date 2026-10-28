package com.zwx.zwxagent.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;

/**
 * JWT 解析本机回环令牌双通道。桌面版由 Electron 主进程生成随机令牌注入后端环境变量，
 * 渲染进程经 preload IPC 拿到同一令牌；仅接受回环来源请求，令牌比较使用常量时间。
 * 令牌未配置（网页部署）时该通道完全关闭。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    static final String LOCAL_TOKEN_HEADER = "X-Aster-Local-Token";

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final String loopbackToken;
    private final String localUsername;

    public JwtAuthFilter(JwtService jwtService,
                         UserRepository userRepository,
                         @Value("${app.desktop.loopback-token:}") String loopbackToken,
                         @Value("${app.security.admin-username:admin}") String localUsername) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.loopbackToken = loopbackToken == null ? "" : loopbackToken.trim();
        this.localUsername = localUsername;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                jwtService.parse(header.substring(7)).ifPresent(this::install);
            } else if (!loopbackToken.isBlank() && matches(request)) {
                localActor().ifPresent(this::install);
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean matches(HttpServletRequest request) {
        String presented = request.getHeader(LOCAL_TOKEN_HEADER);
        if (presented == null || presented.isBlank()) return false;
        return MessageDigest.isEqual(
                loopbackToken.getBytes(StandardCharsets.UTF_8),
                presented.trim().getBytes(StandardCharsets.UTF_8))
                && isLoopback(request);
    }

    private boolean isLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private Optional<CurrentActor> localActor() {
        return userRepository.findByUsername(localUsername)
                .map(user -> new CurrentActor(user.id(), user.tenantId(), user.username(), user.role()));
    }

    private void install(CurrentActor actor) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority(actor.role().authority())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
