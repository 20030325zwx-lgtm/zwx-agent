package com.zwx.zwxagent.creative.skill;

import com.zwx.zwxagent.creative.support.CreativeJson;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * creative_skill / creative_skill_version / creative_skill_run 数据访问。
 * 技能与版本为全局系统数据（MVP 仅内置技能，visibility=SYSTEM）；
 * run 一律带 (tenant_id, user_id) 归属条件，跨用户读写由组合外键在数据库层拒绝。
 */
@Repository
public class CreativeSkillRepository {

    private final JdbcTemplate jdbcTemplate;

    public CreativeSkillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CreativeSkillRecord> SKILL_MAPPER = CreativeSkillRepository::mapSkill;
    private static final RowMapper<CreativeSkillVersionRecord> VERSION_MAPPER = CreativeSkillRepository::mapVersion;
    private static final RowMapper<CreativeSkillRunRecord> RUN_MAPPER = CreativeSkillRepository::mapRun;

    // ---------------------------------------------------------------- 技能与版本

    public void insertSkill(CreativeSkillRecord skill) {
        jdbcTemplate.update("""
                        INSERT INTO creative_skill (id, slug, name, summary, category, stage, input_types,
                            output_types, required_capabilities, visibility, owner_user_id, lifecycle, latest_version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, skill.id());
                    ps.setString(2, skill.slug());
                    ps.setString(3, skill.name());
                    ps.setString(4, skill.summary());
                    ps.setString(5, skill.category());
                    ps.setString(6, skill.stage());
                    ps.setString(7, skill.inputTypes());
                    ps.setString(8, skill.outputTypes());
                    ps.setString(9, CreativeJson.toJson(skill.requiredCapabilities()));
                    ps.setString(10, skill.visibility());
                    ps.setString(11, skill.ownerUserId());
                    ps.setString(12, skill.lifecycle());
                    ps.setInt(13, skill.latestVersion());
                });
    }

    public void insertVersion(CreativeSkillVersionRecord version) {
        jdbcTemplate.update("""
                        INSERT INTO creative_skill_version (id, skill_id, version, body, input_schema, output_schema,
                            source_type, source_ref, content_hash, license_label, license_status)
                        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, version.id());
                    ps.setObject(2, version.skillId());
                    ps.setInt(3, version.version());
                    ps.setString(4, version.body());
                    ps.setString(5, CreativeJson.toJson(version.inputSchema()));
                    ps.setString(6, CreativeJson.toJson(version.outputSchema()));
                    ps.setString(7, version.sourceType());
                    ps.setString(8, version.sourceRef());
                    ps.setString(9, version.contentHash());
                    ps.setString(10, version.licenseLabel());
                    ps.setString(11, version.licenseStatus());
                });
    }

    /** 种子幂等：同 slug 已存在则不覆盖（技能正文升级走显式版本流程，不由启动隐式改写）。 */
    public boolean skillSlugExists(String slug) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creative_skill WHERE slug = ?", Integer.class, slug);
        return count != null && count > 0;
    }

    public boolean versionExists(UUID skillId, int version) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creative_skill_version WHERE skill_id = ? AND version = ?",
                Integer.class, skillId, version);
        return count != null && count > 0;
    }

    public List<CreativeSkillRecord> listPublished() {
        return jdbcTemplate.query(
                "SELECT * FROM creative_skill WHERE lifecycle = 'PUBLISHED' ORDER BY category, slug", SKILL_MAPPER);
    }

    /** 目录可见范围：系统技能 + 本人私有导入（redesign/skill 设计 §8.5 默认私有）。 */
    public List<CreativeSkillRecord> listVisibleFor(String userId) {
        return jdbcTemplate.query("""
                        SELECT * FROM creative_skill
                        WHERE lifecycle = 'PUBLISHED'
                          AND (visibility = 'SYSTEM' OR (visibility = 'PRIVATE' AND owner_user_id = ?))
                        ORDER BY category, slug
                        """, SKILL_MAPPER, userId);
    }

    public Optional<CreativeSkillRecord> findPublishedBySlug(String slug) {
        return jdbcTemplate.query(
                "SELECT * FROM creative_skill WHERE slug = ? AND lifecycle = 'PUBLISHED'",
                SKILL_MAPPER, slug).stream().findFirst();
    }

    public Optional<CreativeSkillRecord> findVisibleBySlug(String slug, String userId) {
        return jdbcTemplate.query("""
                        SELECT * FROM creative_skill
                        WHERE slug = ? AND lifecycle = 'PUBLISHED'
                          AND (visibility = 'SYSTEM' OR (visibility = 'PRIVATE' AND owner_user_id = ?))
                        """, SKILL_MAPPER, slug, userId).stream().findFirst();
    }

    public boolean slugTaken(String slug) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM creative_skill WHERE slug = ?", Integer.class, slug);
        return count != null && count > 0;
    }

    /** 系统技能种子同步：仅更新 visibility=SYSTEM 的行，用户私有导入永不被启动覆盖。 */
    public boolean syncSystemSkillMeta(String slug, String name, String summary, String category,
                                       String stage, String inputTypes, String outputTypes,
                                       List<String> requiredCapabilities) {
        return jdbcTemplate.update("""
                        UPDATE creative_skill SET name = ?, summary = ?, category = ?, stage = ?,
                            input_types = ?, output_types = ?, required_capabilities = ?::jsonb, updated_at = now()
                        WHERE slug = ? AND visibility = 'SYSTEM'
                        """, name, summary, category, stage, inputTypes, outputTypes,
                CreativeJson.toJson(requiredCapabilities), slug) > 0;
    }

    public Optional<CreativeSkillVersionRecord> findVersion(UUID skillId, int version) {
        return jdbcTemplate.query(
                "SELECT * FROM creative_skill_version WHERE skill_id = ? AND version = ?",
                VERSION_MAPPER, skillId, version).stream().findFirst();
    }

    // ---------------------------------------------------------------- 运行

    public void insertRun(CreativeSkillRunRecord run) {
        jdbcTemplate.update("""
                        INSERT INTO creative_skill_run (id, project_id, tenant_id, user_id, skill_id, skill_version_id,
                            input_snapshot, plan, plan_status, media_job_id, fail_reason, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
                        """,
                ps -> {
                    ps.setObject(1, run.id());
                    ps.setObject(2, run.projectId());
                    ps.setString(3, run.tenantId());
                    ps.setString(4, run.userId());
                    ps.setObject(5, run.skillId());
                    ps.setObject(6, run.skillVersionId());
                    ps.setString(7, CreativeJson.toJson(run.inputSnapshot()));
                    ps.setString(8, run.plan() == null ? null : CreativeJson.toJson(run.plan()));
                    ps.setString(9, run.planStatus());
                    ps.setObject(10, run.mediaJobId());
                    ps.setString(11, run.failReason());
                    ps.setLong(12, run.version());
                });
    }

    public Optional<CreativeSkillRunRecord> findRunByIdForOwner(String tenantId, String userId, UUID runId) {
        return jdbcTemplate.query("""
                        SELECT * FROM creative_skill_run WHERE id = ? AND tenant_id = ? AND user_id = ?
                        """, RUN_MAPPER, runId, tenantId, userId).stream().findFirst();
    }

    /** 乐观更新：version 不匹配返回 false，由服务层转 409 语义。 */
    public boolean updateRunPlan(UUID runId, long expectedVersion, Map<String, Object> plan,
                                 String planStatus, String failReason) {
        return jdbcTemplate.update("""
                        UPDATE creative_skill_run SET plan = ?::jsonb, plan_status = ?, fail_reason = ?,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ?
                        """, plan == null ? null : CreativeJson.toJson(plan), planStatus,
                failReason == null ? "" : failReason, runId, expectedVersion) > 0;
    }

    public boolean confirmRun(UUID runId, long expectedVersion, UUID mediaJobId) {
        return jdbcTemplate.update("""
                        UPDATE creative_skill_run SET plan_status = ?, media_job_id = ?,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND plan_status = ?
                        """, CreativeSkillRunRecord.STATUS_CONFIRMED, mediaJobId, runId, expectedVersion,
                CreativeSkillRunRecord.STATUS_PENDING_CONFIRM) > 0;
    }

    /** 逐镜确认：多任务不绑单一 media_job_id，状态仍置 CONFIRMED（P1 MVP）。 */
    public boolean markRunConfirmed(UUID runId, long expectedVersion) {
        return jdbcTemplate.update("""
                        UPDATE creative_skill_run SET plan_status = ?,
                            version = version + 1, updated_at = now()
                        WHERE id = ? AND version = ? AND plan_status = ?
                        """, CreativeSkillRunRecord.STATUS_CONFIRMED, runId, expectedVersion,
                CreativeSkillRunRecord.STATUS_PENDING_CONFIRM) > 0;
    }

    public void insertRunJob(UUID runId, UUID jobId, int shotIndex, String shotTitle) {
        jdbcTemplate.update("""
                        INSERT INTO creative_skill_run_job (id, run_id, job_id, shot_index, shot_title)
                        VALUES (?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), runId, jobId, shotIndex, shotTitle == null ? "" : shotTitle);
    }

    public List<Map<String, Object>> listRunJobs(UUID runId) {
        return jdbcTemplate.query("""
                        SELECT job_id, shot_index, shot_title FROM creative_skill_run_job
                        WHERE run_id = ? ORDER BY shot_index
                        """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobId", rs.getObject("job_id", UUID.class).toString());
            row.put("shotIndex", rs.getInt("shot_index"));
            row.put("shotTitle", rs.getString("shot_title"));
            return row;
        }, runId);
    }

    // ---------------------------------------------------------------- 导入记录（P0-C）

    public void insertImport(UUID id, String tenantId, String userId, String sourceType, String sourceRef,
                             String rawHash, Map<String, Object> parsed) {
        jdbcTemplate.update("""
                        INSERT INTO creative_skill_import (id, tenant_id, user_id, source_type, source_ref,
                            raw_hash, parsed, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'PARSED')
                        """,
                ps -> {
                    ps.setObject(1, id);
                    ps.setString(2, tenantId);
                    ps.setString(3, userId);
                    ps.setString(4, sourceType);
                    ps.setString(5, sourceRef == null ? "" : sourceRef);
                    ps.setString(6, rawHash);
                    ps.setString(7, CreativeJson.toJson(parsed));
                });
    }

    public Optional<Map<String, Object>> findImportByIdForOwner(String tenantId, String userId, UUID id) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, source_type, source_ref, raw_hash, parsed, status,
                               skill_id, error, created_at
                        FROM creative_skill_import WHERE id = ? AND tenant_id = ? AND user_id = ?
                        """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id", UUID.class).toString());
            row.put("sourceType", rs.getString("source_type"));
            row.put("sourceRef", rs.getString("source_ref"));
            row.put("rawHash", rs.getString("raw_hash"));
            row.put("parsed", CreativeJson.readMap(rs.getString("parsed")));
            row.put("status", rs.getString("status"));
            Object skillId = rs.getObject("skill_id", UUID.class);
            row.put("skillId", skillId == null ? null : skillId.toString());
            row.put("error", rs.getString("error"));
            return row;
        }, id, tenantId, userId).stream().findFirst();
    }

    /** 同用户同内容 hash 幂等：重复导入返回已有预检记录。 */
    public Optional<Map<String, Object>> findImportByHashForOwner(String tenantId, String userId, String hash) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, user_id, source_type, source_ref, raw_hash, parsed, status,
                               skill_id, error, created_at
                        FROM creative_skill_import
                        WHERE tenant_id = ? AND user_id = ? AND raw_hash = ? AND status = 'PARSED'
                        ORDER BY created_at DESC LIMIT 1
                        """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getObject("id", UUID.class).toString());
            row.put("sourceType", rs.getString("source_type"));
            row.put("sourceRef", rs.getString("source_ref"));
            row.put("rawHash", rs.getString("raw_hash"));
            row.put("parsed", CreativeJson.readMap(rs.getString("parsed")));
            row.put("status", rs.getString("status"));
            Object skillId = rs.getObject("skill_id", UUID.class);
            row.put("skillId", skillId == null ? null : skillId.toString());
            row.put("error", rs.getString("error"));
            return row;
        }, tenantId, userId, hash).stream().findFirst();
    }

    public boolean confirmImport(UUID id, String tenantId, String userId, UUID skillId) {
        return jdbcTemplate.update("""
                        UPDATE creative_skill_import SET status = 'CONFIRMED', skill_id = ?, updated_at = now()
                        WHERE id = ? AND tenant_id = ? AND user_id = ? AND status = 'PARSED'
                        """, skillId, id, tenantId, userId) > 0;
    }

    // ---------------------------------------------------------------- 映射

    private static CreativeSkillRecord mapSkill(ResultSet rs, int rowNum) throws SQLException {
        return new CreativeSkillRecord(
                rs.getObject("id", UUID.class),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("summary"),
                rs.getString("category"),
                rs.getString("stage"),
                rs.getString("input_types"),
                rs.getString("output_types"),
                CreativeJson.readStringList(rs.getString("required_capabilities")),
                rs.getString("visibility"),
                rs.getString("owner_user_id"),
                rs.getString("lifecycle"),
                rs.getInt("latest_version"));
    }

    private static CreativeSkillVersionRecord mapVersion(ResultSet rs, int rowNum) throws SQLException {
        return new CreativeSkillVersionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("skill_id", UUID.class),
                rs.getInt("version"),
                rs.getString("body"),
                CreativeJson.readMap(rs.getString("input_schema")),
                CreativeJson.readMap(rs.getString("output_schema")),
                rs.getString("source_type"),
                rs.getString("source_ref"),
                rs.getString("content_hash"),
                rs.getString("license_label"),
                rs.getString("license_status"),
                toInstant(rs.getTimestamp("created_at")));
    }

    private static CreativeSkillRunRecord mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new CreativeSkillRunRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("user_id"),
                rs.getObject("skill_id", UUID.class),
                rs.getObject("skill_version_id", UUID.class),
                CreativeJson.readMap(rs.getString("input_snapshot")),
                CreativeJson.readMap(rs.getString("plan")),
                rs.getString("plan_status"),
                rs.getObject("media_job_id", UUID.class),
                rs.getString("fail_reason"),
                rs.getLong("version"),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at")));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
