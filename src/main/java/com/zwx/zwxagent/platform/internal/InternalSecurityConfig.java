package com.zwx.zwxagent.platform.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 内部接口安全配置：/api/internal/** 走 InternalTokenFilter 校验共享令牌，
 * 不再经过 JWT 鉴权链（SecurityConfig 已放行该前缀）。
 */
@Configuration
public class InternalSecurityConfig {

    @Bean
    public InternalTokenFilter internalTokenFilter(@Value("${app.internal.token:}") String internalToken) {
        return new InternalTokenFilter(internalToken);
    }

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilterRegistration(InternalTokenFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        // 过滤器模式相对 context-path（/api），即实际 URI /api/internal/**
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
