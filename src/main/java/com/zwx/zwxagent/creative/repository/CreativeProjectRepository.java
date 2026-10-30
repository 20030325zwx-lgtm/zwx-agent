package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.CreativeProjectRecord;
import com.zwx.zwxagent.creative.domain.ProjectStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * creative_project 数据访问。所有查询必须带归属条件；
 * 跨用户写入由 (id, tenant_id, user_id) 组合外键在数据库层拒绝。
 */
@Repository
public class CreativeProjectRepository {

    private final JdbcTemplate jdbcTemplate;

    public CreativeProjectRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CreativeProjectRecord> MAPPER = CreativeProjectRepository::map;

    public void insert(CreativeProjectRecord project) {
        jdbcTemplate.update("""
                        INSERT INTO creative_project (id, tenant_id, user_id, name, description, status, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, project.id());
                    ps.setString(2, project.tenantId());
                    ps.setString(3, project.userId());
                    ps.setString(4, project.name());
                    ps.setString(5, project.description());
                    ps.setString(6, project.status().name());
                    ps.setLong(7, project.version());
                });
    }

    public Optional<CreativeProjectRecord> findByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, name, description, status, version, created_at, updated_at
                        FROM creative_project WHERE id = ? AND tenant_id = ? AND user_id = ?
                        """,
                MAPPER, id, tenantId, userId).stream().findFirst();
    }

    /** SELECT ... FOR UPDATE：归档与提交互锁（BR15）必须在事务内先锁项目行。 */
    public Optional<CreativeProjectRecord> lockByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, name, description, status, version, created_at, updated_at
                        FROM creative_project WHERE id = ? AND tenant_id = ? AND user_id = ? FOR UPDATE
                        """,
                MAPPER, id, tenantId, userId).stream().findFirst();
    }

    /** 游标分页：(created_at, id) 降序；cursor 为上一页最后一行。 */
    public List<CreativeProjectRecord> listByOwner(String tenantId, String userId,
                                                   Instant cursorCreatedAt, UUID cursorId, int limit) {
        if (cursorCreatedAt == null || cursorId == null) {
            return jdbcTemplate.query("""
                            SELECT id, tenant_id, user_id, name, description, status, version, created_at, updated_at
                            FROM creative_project WHERE tenant_id = ? AND user_id = ?
                            ORDER BY created_at DESC, id DESC LIMIT ?
                            """,
                    MAPPER, tenantId, userId, limit);
        }
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, name, description, status, version, created_at, updated_at
                        FROM creative_project WHERE tenant_id = ? AND user_id = ?
                          AND (created_at, id) < (?, ?)
                        ORDER BY created_at DESC, id DESC LIMIT ?
                        """,
                MAPPER, tenantId, userId, Timestamp.from(cursorCreatedAt), cursorId, limit);
    }

    /** 归档（BR02）：只在 ACTIVE 且版本匹配时成功；返回是否命中。 */
    public boolean archive(UUID id, long expectedVersion) {
        return jdbcTemplate.update("""
                        UPDATE creative_project SET status = ?, version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND status = ?
                        """,
                ProjectStatus.ARCHIVED.name(), id, expectedVersion, ProjectStatus.ACTIVE.name()) > 0;
    }

    /** 元数据更新（名称/描述），乐观锁保护。 */
    public boolean updateMeta(UUID id, long expectedVersion, String name, String description) {
        return jdbcTemplate.update("""
                        UPDATE creative_project SET name = ?, description = ?, version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ?
                        """,
                name, description, id, expectedVersion) > 0;
    }

    /** 组合更新（名称/描述/状态）：归档仅允许 ACTIVE→ARCHIVED；版本不匹配返回 false。 */
    public boolean update(UUID id, long expectedVersion, String name, String description, ProjectStatus target) {
        return jdbcTemplate.update("""
                        UPDATE creative_project SET name = ?, description = ?, status = ?,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ?
                          AND (status = 'ACTIVE' OR (status = 'ARCHIVED' AND ? = 'ARCHIVED'))
                        """,
                ps -> {
                    ps.setString(1, name);
                    ps.setString(2, description);
                    ps.setString(3, target.name());
                    ps.setObject(4, id);
                    ps.setLong(5, expectedVersion);
                    ps.setString(6, target.name());
                }) > 0;
    }

    /** 抢占式项目行锁计数，供调度/提交路径校验项目仍 ACTIVE（配合 FOR UPDATE 使用）。 */
    public Optional<ProjectStatus> statusOf(UUID id) {
        return jdbcTemplate.query("SELECT status FROM creative_project WHERE id = ?",
                (rs, rowNum) -> ProjectStatus.valueOf(rs.getString("status")), id).stream().findFirst();
    }

    private static CreativeProjectRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new CreativeProjectRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("name"),
                rs.getString("description"),
                ProjectStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
