package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.EvalCaseNormalizer;
import com.zwx.aster.eval.domain.EvalCaseRecord;
import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.domain.EvalType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 数据集 / 版本 / 用例管理。发布版本不可变；导入只写草稿（version_no=0）。 */
@Service
public class EvalDatasetService {

    private final JdbcTemplate jdbcTemplate;

    public EvalDatasetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID createDataset(String tenantId, String name, EvalType evalType, String description) {
        UUID id = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                            INSERT INTO platform.eval_dataset (id, tenant_id, name, eval_type, description)
                            VALUES (?, ?, ?, ?, ?)
                            """, id, tenantId, name, evalType.lower(), description);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("同名数据集已存在：" + name);
        }
        return id;
    }

    public List<Map<String, Object>> listDatasets(String tenantId) {
        return jdbcTemplate.queryForList("""
                        SELECT d.id::text, d.name, d.eval_type, d.description,
                               COALESCE(v.latest_version, 0) AS latest_version,
                               COALESCE(c.draft_cases, 0) AS draft_cases,
                               COALESCE(pub.published_cases, 0) AS published_cases,
                               d.created_at
                        FROM platform.eval_dataset d
                        LEFT JOIN (SELECT dataset_id, MAX(version_no) AS latest_version
                                   FROM platform.eval_dataset_version WHERE version_no > 0
                                   GROUP BY dataset_id) v ON v.dataset_id = d.id
                        LEFT JOIN (SELECT dataset_id, COUNT(*) AS draft_cases
                                   FROM platform.eval_case WHERE version_no = 0 GROUP BY dataset_id) c
                                  ON c.dataset_id = d.id
                        LEFT JOIN (SELECT dataset_id, COUNT(*) AS published_cases
                                   FROM platform.eval_case WHERE version_no > 0 GROUP BY dataset_id) pub
                                  ON pub.dataset_id = d.id
                        WHERE d.tenant_id = ?
                        ORDER BY d.created_at DESC
                        """, tenantId);
    }

    public Map<String, Object> getDataset(String tenantId, UUID datasetId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id::text, name, eval_type, description, created_at FROM platform.eval_dataset "
                        + "WHERE id = ? AND tenant_id = ?", datasetId, tenantId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("评测数据集不存在：" + datasetId);
        }
        Map<String, Object> dataset = new LinkedHashMap<>(rows.get(0));
        dataset.put("versions", jdbcTemplate.queryForList("""
                        SELECT version_no, notes, published_at,
                               (SELECT COUNT(*) FROM platform.eval_case c
                                WHERE c.dataset_id = v.dataset_id AND c.version_no = v.version_no) AS case_count
                        FROM platform.eval_dataset_version v
                        WHERE v.dataset_id = ? ORDER BY v.version_no DESC
                        """, datasetId));
        return dataset;
    }

    /** 导入/覆盖草稿用例（version_no=0 upsert）；返回 {total,imported,updated}。 */
    public Map<String, Object> importCases(String tenantId, UUID datasetId, List<Map<String, Object>> rawCases) {
        Map<String, Object> dataset = getDataset(tenantId, datasetId);
        EvalType datasetType = EvalType.parse(String.valueOf(dataset.get("eval_type")));
        int imported = 0;
        int updated = 0;
        for (int i = 0; i < rawCases.size(); i++) {
            EvalCaseNormalizer.NormalizedCase normalized = EvalCaseNormalizer.normalize(rawCases.get(i), i);
            if (normalized.evalType() != datasetType) {
                throw new IllegalArgumentException("用例 " + normalized.externalId() + " 类型 "
                        + normalized.evalType().lower() + " 与数据集类型 " + datasetType.lower() + " 不一致");
            }
            boolean existed = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) > 0 FROM platform.eval_case
                    WHERE dataset_id = ? AND version_no = 0 AND external_id = ?
                    """, Boolean.class, datasetId, normalized.externalId());
            jdbcTemplate.update("""
                            INSERT INTO platform.eval_case (id, tenant_id, dataset_id, version_no, external_id,
                                                            payload, source)
                            VALUES (?, ?, ?, 0, ?, CAST(? AS jsonb), ?)
                            ON CONFLICT (dataset_id, version_no, external_id)
                            DO UPDATE SET payload = CAST(EXCLUDED.payload AS jsonb), source = EXCLUDED.source
                            """,
                    UUID.randomUUID(), tenantId, datasetId, normalized.externalId(),
                    EvalJson.toJson(normalized.payload()), normalized.source());
            if (existed) updated++;
            else imported++;
        }
        return Map.of("total", rawCases.size(), "imported", imported, "updated", updated);
    }

    /** 发布草稿为下一正式版本（克隆草稿用例）。 */
    public int publish(String tenantId, UUID datasetId, String notes) {
        getDataset(tenantId, datasetId);
        Integer draftCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform.eval_case WHERE dataset_id = ? AND version_no = 0",
                Integer.class, datasetId);
        if (draftCount == null || draftCount == 0) {
            throw new IllegalArgumentException("草稿没有用例，无法发布");
        }
        Integer latest = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM platform.eval_dataset_version "
                        + "WHERE dataset_id = ? AND version_no > 0", Integer.class, datasetId);
        int nextVersion = (latest == null ? 0 : latest) + 1;
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO platform.eval_dataset_version (id, tenant_id, dataset_id, version_no, notes) "
                            + "VALUES (?, ?, ?, ?, ?)");
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, tenantId);
            ps.setObject(3, datasetId);
            ps.setInt(4, nextVersion);
            ps.setString(5, notes);
            return ps;
        });
        jdbcTemplate.update("""
                        INSERT INTO platform.eval_case (id, tenant_id, dataset_id, version_no, external_id,
                                                        payload, tags, source)
                        SELECT gen_random_uuid(), tenant_id, dataset_id, ?, external_id, payload, tags, source
                        FROM platform.eval_case WHERE dataset_id = ? AND version_no = 0
                        """, nextVersion, datasetId);
        return nextVersion;
    }

    public List<EvalCaseRecord> publishedCases(String tenantId, UUID datasetId, int versionNo) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, dataset_id, version_no, external_id,
                               payload::text AS payload, source, created_at
                        FROM platform.eval_case
                        WHERE dataset_id = ? AND version_no = ?
                        ORDER BY external_id
                        """,
                (rs, rowNum) -> new EvalCaseRecord(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("tenant_id"),
                        UUID.fromString(rs.getString("dataset_id")),
                        rs.getInt("version_no"),
                        rs.getString("external_id"),
                        EvalJson.parseMap(rs.getString("payload")),
                        rs.getString("source"),
                        rs.getTimestamp("created_at").toInstant()),
                datasetId, versionNo);
    }

    public int latestPublishedVersion(UUID datasetId) {
        Integer latest = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM platform.eval_dataset_version "
                        + "WHERE dataset_id = ? AND version_no > 0", Integer.class, datasetId);
        return latest == null || latest == 0 ? -1 : latest;
    }

    public List<Map<String, Object>> draftCases(String tenantId, UUID datasetId) {
        return jdbcTemplate.queryForList("""
                        SELECT external_id, payload::text AS payload, source
                        FROM platform.eval_case WHERE dataset_id = ? AND version_no = 0
                        ORDER BY external_id
                        """, datasetId);
    }

    public int deleteDataset(String tenantId, UUID datasetId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM platform.eval_dataset WHERE id = ? AND tenant_id = ?", datasetId, tenantId);
        if (deleted > 0) {
            jdbcTemplate.update("DELETE FROM platform.eval_case WHERE dataset_id = ?", datasetId);
            jdbcTemplate.update("DELETE FROM platform.eval_dataset_version WHERE dataset_id = ?", datasetId);
        }
        return deleted;
    }

    /** D1 一键回流的默认归宿：按租户查找/创建「线上回流」PROMPT 数据集。 */
    public UUID findOrCreateBackflowDataset(String tenantId) {
        String name = "线上回流-单轮直调";
        return listDatasets(tenantId).stream()
                .filter(dataset -> name.equals(String.valueOf(dataset.get("name"))))
                .map(dataset -> UUID.fromString(String.valueOf(dataset.get("id"))))
                .findFirst()
                .orElseGet(() -> {
                    try {
                        return createDataset(tenantId, name, EvalType.PROMPT,
                                "BadCase 一键回流（D1 数据中台）：来自用户反馈的回归用例");
                    } catch (IllegalArgumentException exception) {
                        return listDatasets(tenantId).stream()
                                .filter(dataset -> name.equals(String.valueOf(dataset.get("name"))))
                                .map(dataset -> UUID.fromString(String.valueOf(dataset.get("id"))))
                                .findFirst()
                                .orElseThrow(() -> exception);
                    }
                });
    }

    public Optional<UUID> findDraftCaseId(UUID datasetId, String externalId) {
        return jdbcTemplate.query(
                "SELECT id FROM platform.eval_case WHERE dataset_id = ? AND version_no = 0 AND external_id = ?",
                (rs, rowNum) -> UUID.fromString(rs.getString(1)),
                datasetId, externalId).stream().findFirst();
    }

    /** 回流用例构造规则：好评带 golden reference；BadCase 留空 reference、原回答进 notes。 */
    public static Map<String, Object> buildBackflowCase(String feedbackId, String rating, String reason,
                                                        String question, String answer) {
        String notes = "[" + ("up".equals(rating) ? "好评回流" : "BadCase 回流") + "] "
                + (reason == null ? "" : reason);
        if ("down".equals(rating) && answer != null && !answer.isBlank()) {
            notes += "\n[原回答] " + truncate(answer, 500);
        }
        Map<String, Object> rawCase = new LinkedHashMap<>();
        rawCase.put("id", "fb-" + feedbackId.substring(0, 8));
        rawCase.put("system", "");
        rawCase.put("input", question);
        rawCase.put("reference", "up".equals(rating) ? answer : "");
        rawCase.put("expect", Map.of());
        rawCase.put("judge", Map.of("enabled", true, "threshold", 3.5));
        rawCase.put("source", "badcase");
        rawCase.put("notes", notes);
        return rawCase;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
