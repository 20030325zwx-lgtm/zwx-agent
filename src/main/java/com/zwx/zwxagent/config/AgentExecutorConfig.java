package com.zwx.zwxagent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AgentExecutorConfig {

    @Bean("agentExecutor")
    ThreadPoolTaskExecutor agentExecutor(@Value("${app.agent.executor.core-pool-size:4}") int corePoolSize,
                                         @Value("${app.agent.executor.max-pool-size:8}") int maxPoolSize,
                                         @Value("${app.agent.executor.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("agent-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("ragExecutor")
    ThreadPoolTaskExecutor ragExecutor(@Value("${app.rag.executor.core-pool-size:4}") int corePoolSize,
                                       @Value("${app.rag.executor.max-pool-size:8}") int maxPoolSize,
                                       @Value("${app.rag.executor.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("rag-search-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
