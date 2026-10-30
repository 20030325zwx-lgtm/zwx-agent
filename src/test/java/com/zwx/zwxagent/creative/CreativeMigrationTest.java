package com.zwx.zwxagent.creative;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A02（C01）：隔离库上的迁移验证——空库、从 V7 旧基线升级、重复执行无变化、
 * 归属组合外键与幂等唯一键在数据库层生效（A05 的约束部分）。
 * 只使用独立临时库，不触碰用户业务库。
 */
class CreativeMigrationTest {

    private record Creds(String baseUrl, String adminUrl, String username, String password) {
    }

    private final List<String> createdDatabases = new ArrayList<>();
    private Creds creds;

    @BeforeEach
    void resolveCredentials() {
        creds = fromEnvironment().or(CreativeMigrationTest::fromLocalYml).orElse(null);
        Assumptions.assumeTrue(creds != null, "未找到可用的 PostgreSQL 连接配置，跳过迁移验证");
    }

    @AfterEach
    void dropCreatedDatabases() {
        for (String database : createdDatabases) {
            try (Connection connection = DriverManager.getConnection(creds.adminUrl(), creds.username(), creds.password());
                 var statement = connection.prepareStatement("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)")) {
                statement.execute();
            } catch (SQLException ignored) {
                // 清理失败不影响断言；临时库名带时间戳不会复用
            }
        }
        createdDatabases.clear();
    }

    @Test
    void migratesEmptyDatabaseAndRejectsCrossOwnerWrites() throws Exception {
        String database = createScratchDatabase();
        Flyway flyway = flyway(database, null);
        flyway.migrate();

        assertTablesExist(database, "creative_project", "creative_draft", "media_job", "media_job_attempt",
                "media_job_event", "media_job_output", "media_job_asset_ref", "creative_asset",
                "creative_asset_link", "media_model_profile", "media_usage_record", "media_provider_quota");

        UUID projectId = UUID.randomUUID();
        insertProject(database, projectId, "tenant-a", "user-a");
        // 归属一致：草稿与项目必须同一 tenant/user
        assertViolatesForeignKey(database,
                "INSERT INTO creative_draft (id, project_id, tenant_id, user_id, operation) VALUES (?, ?, 'tenant-a', 'user-b', 'text_to_image')",
                UUID.randomUUID(), projectId);
        // 幂等唯一键：同 (tenant, user, key) 第二条必须拒绝
        insertJob(database, projectId, "tenant-a", "user-a", "key-1");
        SQLException duplicate = Assertions.assertThrows(SQLException.class,
                () -> insertJob(database, projectId, "tenant-a", "user-a", "key-1"));
        Assertions.assertEquals("23505", duplicate.getSQLState(), "幂等唯一键必须拒绝重复插入");
        // 相同键不同用户互不影响（隔离性前提）：user-b 需引用自己的项目
        UUID projectB = UUID.randomUUID();
        insertProject(database, projectB, "tenant-a", "user-b");
        insertJob(database, projectB, "tenant-a", "user-b", "key-1");
    }

    @Test
    void upgradesLegacyBaselinePreservingExistingRows() throws Exception {
        String database = createScratchDatabase();
        flyway(database, "7").migrate();
        try (Connection connection = connect(database)) {
            try (var statement = connection.prepareStatement("""
                            INSERT INTO model_provider (owner_id, name, protocol, base_url, enabled)
                            VALUES ('tenant-a', 'creative-legacy-check', 'dashscope', NULL, TRUE)
                            """)) {
                statement.execute();
            }
        }
        flyway(database, null).migrate();
        try (Connection connection = connect(database)) {
            try (var statement = connection.prepareStatement(
                    "SELECT count(*) FROM model_provider WHERE name = 'creative-legacy-check'")) {
                ResultSet rs = statement.executeQuery();
                rs.next();
                Assertions.assertEquals(1, rs.getInt(1), "V7→V8 升级后旧数据必须原样保留");
            }
        }
        assertTablesExist(database, "media_job", "creative_asset");
    }

    @Test
    void repeatedMigrationIsIdempotent() throws Exception {
        String database = createScratchDatabase();
        Flyway flyway = flyway(database, null);
        flyway.migrate();
        int appliedBefore = historyRowCount(database);
        flyway.migrate();
        Assertions.assertEquals(appliedBefore, historyRowCount(database), "重复迁移不应产生任何变更");
    }

    private int historyRowCount(String database) throws SQLException {
        try (Connection connection = connect(database);
             var statement = connection.prepareStatement("SELECT count(*) FROM flyway_schema_history")) {
            ResultSet rs = statement.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }

    // ---------------------------------------------------------------- 工具

    private Flyway flyway(String database, String target) {
        var builder = Flyway.configure()
                .dataSource(jdbcUrl(database), creds.username(), creds.password())
                .locations("classpath:db/migration")
                .baselineOnMigrate(true);
        if (target != null) builder.target(target);
        return builder.load();
    }

    private String createScratchDatabase() throws SQLException {
        String database = "creative_c01_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(creds.adminUrl(), creds.username(), creds.password());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        createdDatabases.add(database);
        return database;
    }

    private Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(database), creds.username(), creds.password());
    }

    private String jdbcUrl(String database) {
        return creds.baseUrl() + "/" + database;
    }

    private void assertTablesExist(String database, String... tables) throws SQLException {
        try (Connection connection = connect(database)) {
            for (String table : tables) {
                try (var statement = connection.prepareStatement("""
                                SELECT count(*) FROM information_schema.tables
                                WHERE table_schema = 'public' AND table_name = ?
                                """)) {
                    statement.setString(1, table);
                    ResultSet rs = statement.executeQuery();
                    rs.next();
                    Assertions.assertEquals(1, rs.getInt(1), "缺少表：" + table);
                }
            }
        }
    }

    private void insertProject(String database, UUID projectId, String tenantId, String userId) throws SQLException {
        try (Connection connection = connect(database);
             var statement = connection.prepareStatement("""
                             INSERT INTO creative_project (id, tenant_id, user_id, name) VALUES (?, ?, ?, ?)
                             """)) {
            statement.setObject(1, projectId);
            statement.setString(2, tenantId);
            statement.setString(3, userId);
            statement.setString(4, "校验项目");
            statement.execute();
        }
    }

    private void insertJob(String database, UUID projectId, String tenantId, String userId,
                           String idempotencyKey) throws SQLException {
        try (Connection connection = connect(database);
             var statement = connection.prepareStatement("""
                             INSERT INTO media_job (id, project_id, tenant_id, user_id, source_type, operation,
                                 idempotency_key, request_hash, input_snapshot, model_snapshot)
                             VALUES (?, ?, ?, ?, 'TEST', 'text_to_image', ?, 'hash', '{}', '{}')
                             """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, projectId);
            statement.setString(3, tenantId);
            statement.setString(4, userId);
            statement.setString(5, idempotencyKey);
            statement.execute();
        }
    }

    private void assertViolatesForeignKey(String database, String sql, Object... args) throws SQLException {
        try (Connection connection = connect(database);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            SQLException exception = Assertions.assertThrows(SQLException.class, statement::execute);
            Assertions.assertEquals("23503", exception.getSQLState(), "应为外键违约：" + sql);
        }
    }

    private static Optional<Creds> fromEnvironment() {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        String username = System.getenv("SPRING_DATASOURCE_USERNAME");
        String password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        if (url == null || username == null || password == null) return Optional.empty();
        String prefix = serverPrefixOf(url);
        return Optional.of(new Creds(prefix, prefix + "/postgres", username, password));
    }

    /**
     * 本地开发：从 application-local.yml 解析 spring.datasource（仅测试进程读取，不输出内容）。
     */
    private static Optional<Creds> fromLocalYml() {
        try {
            var path = java.nio.file.Path.of("src/main/resources/application-local.yml");
            if (!java.nio.file.Files.exists(path)) return Optional.empty();
            String url = null;
            String username = null;
            String password = null;
            for (String line : java.nio.file.Files.readAllLines(path)) {
                if (line.matches("^    url: .*")) url = value(line);
                else if (line.matches("^    username: .*")) username = value(line);
                else if (line.matches("^    password: .*")) password = value(line);
            }
            if (url == null || username == null || password == null) return Optional.empty();
            String prefix = serverPrefixOf(url);
            return Optional.of(new Creds(prefix, prefix + "/postgres", username, password));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private static String value(String line) {
        return line.substring(line.indexOf(':') + 1).trim();
    }

    /** jdbc:postgresql://host:port/db → jdbc:postgresql://host:port */
    private static String serverPrefixOf(String url) {
        int slash = url.lastIndexOf('/');
        return url.substring(0, slash);
    }
}
