package com.zwx.zwxagent.platform.config;

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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("knowledgeIndexExecutor")
    ThreadPoolTaskExecutor knowledgeIndexExecutor(@Value("${app.knowledge.index.executor.core-pool-size:2}") int corePoolSize,
                                                  @Value("${app.knowledge.index.executor.max-pool-size:4}") int maxPoolSize,
                                                  @Value("${app.knowledge.index.executor.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("knowledge-index-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("graphWorkerExecutor")
    ThreadPoolTaskExecutor graphWorkerExecutor(@Value("${app.graph.executor.core-pool-size:6}") int corePoolSize,
                                               @Value("${app.graph.executor.max-pool-size:12}") int maxPoolSize,
                                               @Value("${app.graph.executor.queue-capacity:50}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("graph-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
