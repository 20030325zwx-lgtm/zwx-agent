package com.zwx.zwxagent.config;

import com.zwx.zwxagent.security.CurrentActorArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentActorArgumentResolver currentActorArgumentResolver;

    public WebMvcConfig(CurrentActorArgumentResolver currentActorArgumentResolver) {
        this.currentActorArgumentResolver = currentActorArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentActorArgumentResolver);
    }
}
