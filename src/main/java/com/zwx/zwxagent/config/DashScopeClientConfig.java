package com.zwx.zwxagent.config;

import com.alibaba.dashscope.protocol.ConnectionConfigurations;
import com.alibaba.dashscope.utils.Constants;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Slf4j
public class DashScopeClientConfig {

    @PostConstruct
    public void configureDashScopeTimeouts() {
        ConnectionConfigurations configurations = Constants.connectionConfigurations != null
                ? Constants.connectionConfigurations
                : ConnectionConfigurations.builder().build();
        configurations.setConnectTimeout(Duration.ofSeconds(10));
        configurations.setWriteTimeout(Duration.ofSeconds(30));
        // 流式响应下 readTimeout 是字节间的最大空闲时间，而不是整个响应的时长
        configurations.setReadTimeout(Duration.ofSeconds(60));
        Constants.connectionConfigurations = configurations;
        log.info("DashScope client timeouts configured: connect=10s, write=30s, read(idle)=60s");
    }
}
