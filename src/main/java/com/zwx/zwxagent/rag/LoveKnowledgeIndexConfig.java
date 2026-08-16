package com.zwx.zwxagent.rag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class LoveKnowledgeIndexConfig {

    @Bean("loveKnowledgeIndexExecutor")
    TaskExecutor loveKnowledgeIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("love-knowledge-index-");
        executor.initialize();
        return executor;
    }
}
