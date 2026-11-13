package com.zwx.zwxagent.knowledge.vector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 启动期预热：幂等确保 Weaviate Collection 存在，尽早暴露连接/鉴权/容量问题。
 * 与 pgvector 路径的 {@code KnowledgeVectorSchemaInitializer} 同一容错约定——
 * 失败只 warn 不阻断启动（检索期由 {@link WeaviateKnowledgeVectorStore} 惰性重试）。
 */
@Component
@ConditionalOnProperty(name = "app.vector-store.provider", havingValue = "weaviate")
public class WeaviateSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WeaviateSchemaInitializer.class);

    private final KnowledgeVectorStore vectorStore;

    public WeaviateSchemaInitializer(@Qualifier("agentKnowledgeVectorStore") KnowledgeVectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!(vectorStore instanceof WeaviateKnowledgeVectorStore weaviate)) {
            log.warn("app.vector-store.provider=weaviate 但向量存储实现不匹配：{}", vectorStore.getClass().getSimpleName());
            return;
        }
        try {
            weaviate.ensureReady();
        } catch (Exception exception) {
            log.warn("Weaviate Collection 预热失败（不影响启动，检索期惰性重试）：{}", exception.getMessage());
        }
    }
}
