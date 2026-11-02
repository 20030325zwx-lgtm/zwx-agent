package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.CreativeKind;
import com.zwx.zwxagent.creative.domain.CreativeProjectRecord;
import com.zwx.zwxagent.creative.domain.ProjectStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
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
                        INSERT INTO creative_project (id, tenant_id, user_id, name, description, kind, status, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, project.id());
                    ps.setString(2, project.tenantId());
                    ps.setString(3, project.userId());
                    ps.setString(4, project.name());
                    ps.setString(5, project.description());
                    ps.setString(6, project.kind().name());
                    ps.setString(7, project.status().name());
                    ps.setLong(8, project.version());
                });
    }

    public Optional<CreativeProjectRecord> findByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, name, description, kind, status, version, created_at, updated_at
                        FROM creative_project WHERE id = ? AND tenant_id = ? AND user_id = ?
                        """,
                MAPPER, id, tenantId, userId).stream().findFirst();
    }

    /** SELECT ... FOR UPDATE：归档与提交互锁（BR15）必须在事务内先锁项目行。 */
    public Optional<CreativeProjectRecord> lockByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, name, description, kind, status, version, created_at, updated_at
                        FROM creative_project WHERE id = ? AND tenant_id = ? AND user_id = ? FOR UPDATE
                        """,
                MAPPER, id, tenantId, userId).stream().findFirst();
    }

    /** 列表排序：最近更新（默认，走 V8 索引）与创建时间。 */
    public enum Sort {
        UPDATED("updated_at"),
        CREATED("created_at");

        private final String column;

        Sort(String column) {
            this.column = column;
        }

        public static Sort parse(String value) {
            if (value == null || value.isBlank() || "updated".equalsIgnoreCase(value.trim())) return UPDATED;
            if ("created".equalsIgnoreCase(value.trim())) return CREATED;
            throw new IllegalArgumentException("不支持的排序方式：" + value);
        }
    }

    /** 列表行：项目本身 + 即时计算的封面（最新 READY 素材，图片优先）。 */
    public record ProjectListRow(CreativeProjectRecord project, UUID coverAssetId, String coverMediaType) {
    }

    private static final RowMapper<ProjectListRow> LIST_MAPPER = (rs, rowNum) -> new ProjectListRow(
            map(rs, rowNum),
            rs.getString("cover_asset_id") == null ? null : UUID.fromString(rs.getString("cover_asset_id")),
            rs.getString("cover_media_type"));

    /**
     * 游标分页列表：按 owner 过滤，可选类型/关键词；默认 (updated_at, id) 降序。
     * 封面用一条 LATERAL 子查询取每个项目最新 READY 素材，避免前端按项目逐条反查（N+1）。
     */
    public List<ProjectListRow> listByOwner(String tenantId, String userId, CreativeKind kind, String keyword,
                                            Sort sort, Instant cursorAt, UUID cursorId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.tenant_id, p.user_id, p.name, p.description, p.kind, p.status, p.version,
                       p.created_at, p.updated_at, cov.asset_id AS cover_asset_id, cov.media_type AS cover_media_type
                FROM creative_project p
                LEFT JOIN LATERAL (
                    SELECT a.id AS asset_id, a.media_type AS media_type
                    FROM creative_asset a
                    WHERE a.project_id = p.id AND a.tenant_id = p.tenant_id AND a.user_id = p.user_id
                      AND a.status = 'READY'
                    ORDER BY (a.media_type = 'IMAGE') DESC, a.created_at DESC, a.id DESC
                    LIMIT 1
                ) cov ON TRUE
                WHERE p.tenant_id = ? AND p.user_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        if (kind != null) {
            sql.append(" AND p.kind = ?");
            args.add(kind.name());
        }
        if (keyword != null) {
            sql.append(" AND (p.name ILIKE ? OR p.description ILIKE ?)");
            args.add(keyword);
            args.add(keyword);
        }
        String column = "p." + sort.column;
        if (cursorAt != null && cursorId != null) {
            sql.append(" AND (").append(column).append(", p.id) < (?, ?)");
            args.add(Timestamp.from(cursorAt));
            args.add(cursorId);
        }
        sql.append(" ORDER BY ").append(column).append(" DESC, p.id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), LIST_MAPPER, args.toArray());
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

    /** 项目设定 JSON（V13，长篇创作共用；小说约定 {characters, worldview, style}）。 */
    public Optional<String> settingsJsonOf(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query(
                "SELECT settings_json::text FROM creative_project WHERE id = ? AND tenant_id = ? AND user_id = ?",
                (rs, rowNum) -> rs.getString(1), id, tenantId, userId).stream().findFirst();
    }

    /** 整体覆盖项目设定（服务层负责合并与校验）。 */
    public void updateSettings(UUID id, String settingsJson) {
        jdbcTemplate.update("UPDATE creative_project SET settings_json = ?::jsonb, updated_at = now() WHERE id = ?",
                settingsJson, id);
    }

    private static CreativeProjectRecord map(ResultSet rs, int rowNum) throws SQLException {
        return new CreativeProjectRecord(
                UUID.fromString(rs.getString("id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getString("name"),
                rs.getString("description"),
                CreativeKind.valueOf(rs.getString("kind")),
                ProjectStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
