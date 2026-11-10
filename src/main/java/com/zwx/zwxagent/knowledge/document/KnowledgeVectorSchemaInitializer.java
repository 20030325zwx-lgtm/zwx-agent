package com.zwx.zwxagent.knowledge.document;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 向量表结构校正（2026-11-05）：
 * {@code agent_knowledge_vector} 由 Spring AI PgVectorStore 在启动时自建，DDL 里 metadata 是 {@code json}。
 * {@code json} 没有 GIN/表达式索引能力，导致检索时的 tenantId/agentKey/lifecycleStatus 过滤与
 * 词法通道的 {@code ILIKE '%词%'} 都退化成全表扫 + 逐行解析。
 *
 * <p>PgVectorStore 的写入语句本身是 {@code ?::jsonb}，改成 jsonb 完全兼容。这里在容器启动后
 * （PgVectorStore 已建表）做一次幂等校正：json → jsonb，并补齐过滤/词法索引。
 * 不使用 Flyway，是因为新库首次部署时该表还不存在。</p>
 */
@Component
public class KnowledgeVectorSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeVectorSchemaInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeVectorSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            String type = metadataType();
            if (type == null) {
                log.info("向量表结构校正：agent_knowledge_vector 尚未创建，跳过");
                return;
            }
            if ("json".equalsIgnoreCase(type)) {
                long startedAt = System.currentTimeMillis();
                jdbcTemplate.execute(
                        "ALTER TABLE agent_knowledge_vector ALTER COLUMN metadata TYPE jsonb USING metadata::jsonb");
                log.info("向量表结构校正：metadata json → jsonb 完成，耗时 {} ms",
                        System.currentTimeMillis() - startedAt);
            }
            ensureIndexes();
        } catch (RuntimeException exception) {
            // 结构校正失败不阻断启动：检索仍可用，只是继续走全表扫
            log.warn("向量表结构校正失败（不影响启动）：{}", exception.getMessage());
        }
    }

    private String metadataType() {
        var types = jdbcTemplate.queryForList("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'agent_knowledge_vector' AND column_name = 'metadata'
                """, String.class);
        return types.isEmpty() ? null : types.getFirst();
    }

    private void ensureIndexes() {
        // 语料规模大时这两个表达式索引是检索过滤能否走索引的关键
        create("CREATE INDEX IF NOT EXISTS idx_kv_scope ON agent_knowledge_vector "
                + "((metadata ->> 'tenantId'), (metadata ->> 'agentKey'), (metadata ->> 'lifecycleStatus'))");
        create("CREATE INDEX IF NOT EXISTS idx_kv_document ON agent_knowledge_vector "
                + "((metadata ->> 'documentId'))");
        // 兄弟扩展按「同文档同章节」直查：复合表达式索引避免 JSONB 逐行过滤
        create("CREATE INDEX IF NOT EXISTS idx_kv_document_section ON agent_knowledge_vector "
                + "((metadata ->> 'documentId'), (metadata ->> 'sectionPath'))");
        // 词法通道按关键词匹配正文；pg_trgm 由 V42 迁移安装
        create("CREATE INDEX IF NOT EXISTS idx_kv_content_trgm ON agent_knowledge_vector "
                + "USING gin (content gin_trgm_ops)");
        create("CREATE INDEX IF NOT EXISTS idx_kv_metadata_gin ON agent_knowledge_vector "
                + "USING gin (metadata jsonb_path_ops)");
    }

    private void create(String ddl) {
        try {
            jdbcTemplate.execute(ddl);
        } catch (RuntimeException exception) {
            log.warn("向量表索引创建失败（可忽略，稍后重试）：{}；{}", ddl, exception.getMessage());
        }
    }
}
