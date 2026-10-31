package com.zwx.zwxagent.creative.repository;

import com.zwx.zwxagent.creative.domain.ChapterStatus;
import com.zwx.zwxagent.creative.domain.NovelChapterRecord;
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
 * novel_chapter 数据访问（V13）。所有查询必须带归属条件；
 * (project_id, tenant_id, user_id) 组合外键在数据库层拒绝跨用户关联。
 */
@Repository
public class NovelChapterRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<NovelChapterRecord> MAPPER = NovelChapterRepository::map;

    public NovelChapterRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(NovelChapterRecord chapter) {
        jdbcTemplate.update("""
                        INSERT INTO novel_chapter (id, project_id, tenant_id, user_id, chapter_number, title,
                                                   plot_direction, content, summary, word_count, status, source_run_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, chapter.id());
                    ps.setObject(2, chapter.projectId());
                    ps.setString(3, chapter.tenantId());
                    ps.setString(4, chapter.userId());
                    ps.setInt(5, chapter.chapterNumber());
                    ps.setString(6, chapter.title());
                    ps.setString(7, chapter.plotDirection());
                    ps.setString(8, chapter.content());
                    ps.setString(9, chapter.summary());
                    ps.setInt(10, chapter.wordCount());
                    ps.setString(11, chapter.status().name());
                    ps.setObject(12, chapter.sourceRunId());
                });
    }

    /** 章节列表按序号升序（目录顺序）。 */
    public List<NovelChapterRecord> listByProject(String tenantId, String userId, UUID projectId) {
        return jdbcTemplate.query("""
                        SELECT id, project_id, tenant_id, user_id, chapter_number, title, plot_direction,
                               content, summary, word_count, status, source_run_id, created_at, updated_at
                        FROM novel_chapter
                        WHERE project_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY chapter_number ASC, created_at ASC
                        """, MAPPER, projectId, tenantId, userId);
    }

    public Optional<NovelChapterRecord> findByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, project_id, tenant_id, user_id, chapter_number, title, plot_direction,
                               content, summary, word_count, status, source_run_id, created_at, updated_at
                        FROM novel_chapter
                        WHERE id = ? AND tenant_id = ? AND user_id = ?
                        """, MAPPER, id, tenantId, userId).stream().findFirst();
    }

    /** 同一运行重复入库（崩溃重跑）时定位已有草稿，保证幂等。 */
    public Optional<NovelChapterRecord> findByRun(String tenantId, String userId, UUID projectId, UUID runId) {
        return jdbcTemplate.query("""
                        SELECT id, project_id, tenant_id, user_id, chapter_number, title, plot_direction,
                               content, summary, word_count, status, source_run_id, created_at, updated_at
                        FROM novel_chapter
                        WHERE project_id = ? AND source_run_id = ? AND tenant_id = ? AND user_id = ?
                        ORDER BY created_at ASC LIMIT 1
                        """, MAPPER, projectId, runId, tenantId, userId).stream().findFirst();
    }

    public int nextChapterNumber(UUID projectId) {
        List<Integer> rows = jdbcTemplate.query(
                "SELECT COALESCE(MAX(chapter_number), 0) + 1 FROM novel_chapter WHERE project_id = ?",
                (rs, rowNum) -> rs.getInt(1), projectId);
        return rows.isEmpty() ? 1 : rows.getFirst();
    }

    /** 编辑章节（标题/走向/正文/概要），字数由服务层重算后传入。 */
    public boolean updateContent(UUID id, String title, String plotDirection,
                                 String content, String summary, int wordCount) {
        return jdbcTemplate.update("""
                        UPDATE novel_chapter SET title = ?, plot_direction = ?, content = ?, summary = ?,
                            word_count = ?, updated_at = now()
                        WHERE id = ?
                        """, title, plotDirection, content, summary, wordCount, id) > 0;
    }

    public boolean updateStatus(UUID id, ChapterStatus status) {
        return jdbcTemplate.update(
                "UPDATE novel_chapter SET status = ?, updated_at = now() WHERE id = ?",
                status.name(), id) > 0;
    }

    public boolean delete(UUID id) {
        return jdbcTemplate.update("DELETE FROM novel_chapter WHERE id = ?", id) > 0;
    }

    private static NovelChapterRecord map(ResultSet rs, int rowNum) throws SQLException {
        UUID runId = null;
        Object runValue = rs.getObject("source_run_id");
        if (runValue instanceof UUID uuid) runId = uuid;
        else if (runValue != null) runId = UUID.fromString(runValue.toString());
        return new NovelChapterRecord(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("project_id")),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getInt("chapter_number"),
                rs.getString("title"),
                rs.getString("plot_direction"),
                rs.getString("content"),
                rs.getString("summary"),
                rs.getInt("word_count"),
                ChapterStatus.valueOf(rs.getString("status")),
                runId,
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
