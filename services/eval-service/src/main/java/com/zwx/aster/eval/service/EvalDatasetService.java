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

/**
 * 数据集 / 版本 / 用例管理。发布版本不可变；导入与单条编辑只写草稿（version_no=0）。
 * 所有读写都带 tenant 归属校验（数据集 UUID 不是访问凭据）。
 */
@Service
public class EvalDatasetService {

    private static final String TAGS_FROM_JSON = "ARRAY(SELECT jsonb_array_elements_text(CAST(? AS jsonb)))";

    private final JdbcTemplate jdbcTemplate;

    public EvalDatasetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---------------------------------------------------------------- 数据集

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

    public void updateDataset(String tenantId, UUID datasetId, String name, String description) {
        Map<String, Object> existing = getDataset(tenantId, datasetId);
        String nextName = name == null || name.isBlank() ? String.valueOf(existing.get("name")) : name.trim();
        String nextDescription = description == null ? stringOrNull(existing.get("description")) : description;
        try {
            jdbcTemplate.update("""
                    UPDATE platform.eval_dataset SET name = ?, description = ?, updated_at = now()
                    WHERE id = ? AND tenant_id = ?
                    """, nextName, nextDescription, datasetId, tenantId);
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("同名数据集已存在：" + nextName);
        }
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

    public int deleteDataset(String tenantId, UUID datasetId) {
        int deleted = jdbcTemplate.update(
                "DELETE FROM platform.eval_dataset WHERE id = ? AND tenant_id = ?", datasetId, tenantId);
        if (deleted > 0) {
            jdbcTemplate.update("DELETE FROM platform.eval_case WHERE dataset_id = ? AND tenant_id = ?",
                    datasetId, tenantId);
            jdbcTemplate.update("DELETE FROM platform.eval_dataset_version WHERE dataset_id = ? AND tenant_id = ?",
                    datasetId, tenantId);
        }
        return deleted;
    }

    // ---------------------------------------------------------------- 用例（草稿）

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
            Map<String, Object> raw = rawCases.get(i);
            boolean existed = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) > 0 FROM platform.eval_case
                    WHERE dataset_id = ? AND tenant_id = ? AND version_no = 0 AND external_id = ?
                    """, Boolean.class, datasetId, tenantId, normalized.externalId());
            jdbcTemplate.update("""
                            INSERT INTO platform.eval_case (id, tenant_id, dataset_id, version_no, external_id,
                                                            payload, tags, severity, source)
                            VALUES (?, ?, ?, 0, ?, CAST(? AS jsonb), %s, ?, ?)
                            ON CONFLICT (dataset_id, version_no, external_id)
                            DO UPDATE SET payload = CAST(EXCLUDED.payload AS jsonb),
                                          tags = EXCLUDED.tags,
                                          severity = EXCLUDED.severity,
                                          source = EXCLUDED.source
                            """.formatted(TAGS_FROM_JSON),
                    UUID.randomUUID(), tenantId, datasetId, normalized.externalId(),
                    EvalJson.toJson(normalized.payload()), EvalJson.toJson(tagsOf(raw)),
                    severityOf(raw), normalized.source());
            if (existed) updated++;
            else imported++;
        }
        return Map.of("total", rawCases.size(), "imported", imported, "updated", updated);
    }

    /** 单条新增到草稿。 */
    public Map<String, Object> addCase(String tenantId, UUID datasetId, Map<String, Object> body) {
        Map<String, Object> dataset = getDataset(tenantId, datasetId);
        EvalType datasetType = EvalType.parse(String.valueOf(dataset.get("eval_type")));
        Map<String, Object> payload = payloadOf(body);
        assertPayloadType(datasetType, payload);
        String externalId = stringOrNull(body.get("externalId"));
        if (externalId == null || externalId.isBlank()) {
            externalId = "case-" + UUID.randomUUID().toString().substring(0, 8);
        }
        jdbcTemplate.update("""
                        INSERT INTO platform.eval_case (id, tenant_id, dataset_id, version_no, external_id,
                                                        payload, tags, severity, source)
                        VALUES (?, ?, ?, 0, ?, CAST(? AS jsonb), %s, ?, 'manual')
                        ON CONFLICT (dataset_id, version_no, external_id)
                        DO UPDATE SET payload = CAST(EXCLUDED.payload AS jsonb), tags = EXCLUDED.tags,
                                      severity = EXCLUDED.severity
                        """.formatted(TAGS_FROM_JSON),
                UUID.randomUUID(), tenantId, datasetId, externalId, EvalJson.toJson(payload),
                EvalJson.toJson(tagsOf(body)), severityOf(body));
        return Map.of("externalId", externalId);
    }

    /** 修改草稿用例（已发布版本不可改）。 */
    public void updateCase(String tenantId, UUID datasetId, String externalId, Map<String, Object> body) {
        Map<String, Object> dataset = getDataset(tenantId, datasetId);
        EvalType datasetType = EvalType.parse(String.valueOf(dataset.get("eval_type")));
        Map<String, Object> payload = payloadOf(body);
        assertPayloadType(datasetType, payload);
        int updated = jdbcTemplate.update("""
                        UPDATE platform.eval_case
                        SET payload = CAST(? AS jsonb), tags = %s, severity = ?
                        WHERE dataset_id = ? AND tenant_id = ? AND version_no = 0 AND external_id = ?
                        """.formatted(TAGS_FROM_JSON),
                EvalJson.toJson(payload), EvalJson.toJson(tagsOf(body)), severityOf(body),
                datasetId, tenantId, externalId);
        if (updated == 0) throw new IllegalArgumentException("草稿用例不存在：" + externalId);
    }

    public void deleteCase(String tenantId, UUID datasetId, String externalId) {
        getDataset(tenantId, datasetId);
        int deleted = jdbcTemplate.update("""
                DELETE FROM platform.eval_case
                WHERE dataset_id = ? AND tenant_id = ? AND version_no = 0 AND external_id = ?
                """, datasetId, tenantId, externalId);
        if (deleted == 0) throw new IllegalArgumentException("草稿用例不存在：" + externalId);
    }

    // ---------------------------------------------------------------- 版本

    /** 发布草稿为下一正式版本（克隆草稿用例）。 */
    public int publish(String tenantId, UUID datasetId, String notes) {
        getDataset(tenantId, datasetId);
        Integer draftCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform.eval_case WHERE dataset_id = ? AND tenant_id = ? AND version_no = 0",
                Integer.class, datasetId, tenantId);
        if (draftCount == null || draftCount == 0) {
            throw new IllegalArgumentException("草稿没有用例，无法发布");
        }
        Integer latest = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM platform.eval_dataset_version "
                        + "WHERE dataset_id = ? AND version_no > 0", Integer.class, datasetId);
        int nextVersion = (latest == null ? 0 : latest) + 1;
        jdbcTemplate.update("""
                INSERT INTO platform.eval_dataset_version (id, tenant_id, dataset_id, version_no, notes)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, datasetId, nextVersion, notes);
        jdbcTemplate.update("""
                        INSERT INTO platform.eval_case (id, tenant_id, dataset_id, version_no, external_id,
                                                        payload, tags, severity, source)
                        SELECT gen_random_uuid(), tenant_id, dataset_id, ?, external_id, payload, tags, severity, source
                        FROM platform.eval_case WHERE dataset_id = ? AND version_no = 0
                        """, nextVersion, datasetId);
        return nextVersion;
    }

    /** 从历史版本 fork 回草稿（改坏了的版本可以回退，而不是重新导入）。 */
    public Map<String, Object> forkVersion(String tenantId, UUID datasetId, int versionNo, boolean confirm) {
        getDataset(tenantId, datasetId);
        Integer draftCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM platform.eval_case WHERE dataset_id = ? AND tenant_id = ? AND version_no = 0",
                Integer.class, datasetId, tenantId);
        int replaced = draftCount == null ? 0 : draftCount;
        if (replaced > 0 && !confirm) {
            throw new IllegalArgumentException("草稿已有 " + replaced + " 条用例，覆盖需确认（confirm=true）");
        }
        Integer sourceCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM platform.eval_case
                WHERE dataset_id = ? AND tenant_id = ? AND version_no = ?
                """, Integer.class, datasetId, tenantId, versionNo);
        if (sourceCount == null || sourceCount == 0) {
            throw new IllegalArgumentException("版本 v" + versionNo + " 没有用例");
        }
        jdbcTemplate.update("DELETE FROM platform.eval_case WHERE dataset_id = ? AND tenant_id = ? AND version_no = 0",
                datasetId, tenantId);
        jdbcTemplate.update("""
                        INSERT INTO platform.eval_case (id, tenant_id, dataset_id, version_no, external_id,
                                                        payload, tags, severity, source)
                        SELECT gen_random_uuid(), tenant_id, dataset_id, 0, external_id, payload, tags, severity, source
                        FROM platform.eval_case WHERE dataset_id = ? AND version_no = ?
                        """, datasetId, versionNo);
        return Map.of("replaced", replaced, "imported", sourceCount, "fromVersion", versionNo);
    }

    public List<EvalCaseRecord> publishedCases(String tenantId, UUID datasetId, int versionNo) {
        return jdbcTemplate.query("""
                        SELECT id, tenant_id, dataset_id, version_no, external_id,
                               payload::text AS payload, array_to_string(tags, ',') AS tags,
                               severity, source, created_at
                        FROM platform.eval_case
                        WHERE dataset_id = ? AND tenant_id = ? AND version_no = ?
                        ORDER BY external_id
                        """,
                (rs, rowNum) -> new EvalCaseRecord(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("tenant_id"),
                        UUID.fromString(rs.getString("dataset_id")),
                        rs.getInt("version_no"),
                        rs.getString("external_id"),
                        EvalJson.parseMap(rs.getString("payload")),
                        splitTags(rs.getString("tags")),
                        rs.getString("severity"),
                        rs.getString("source"),
                        rs.getTimestamp("created_at").toInstant()),
                datasetId, tenantId, versionNo);
    }

    public int latestPublishedVersion(UUID datasetId) {
        Integer latest = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM platform.eval_dataset_version "
                        + "WHERE dataset_id = ? AND version_no > 0", Integer.class, datasetId);
        return latest == null || latest == 0 ? -1 : latest;
    }

    public List<Map<String, Object>> draftCases(String tenantId, UUID datasetId) {
        return jdbcTemplate.queryForList("""
                        SELECT external_id, payload::text AS payload, array_to_string(tags, ',') AS tags,
                               severity, source
                        FROM platform.eval_case
                        WHERE dataset_id = ? AND tenant_id = ? AND version_no = 0
                        ORDER BY external_id
                        """, datasetId, tenantId);
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
        rawCase.put("severity", "down".equals(rating) ? "critical" : "normal");
        rawCase.put("notes", notes);
        return rawCase;
    }

    // ---------------------------------------------------------------- 工具

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Map<String, Object> body) {
        Object payload = body.get("payload");
        if (payload instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        // 兼容「直接把字段放在顶层」的写法
        Map<String, Object> flat = new LinkedHashMap<>(body);
        flat.remove("externalId");
        flat.remove("tags");
        flat.remove("severity");
        flat.remove("payload");
        if (flat.isEmpty()) throw new IllegalArgumentException("payload 不能为空");
        return flat;
    }

    private static void assertPayloadType(EvalType datasetType, Map<String, Object> payload) {
        EvalType inferred;
        try {
            inferred = EvalCaseNormalizer.inferType(payload);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("无法识别用例类型：" + exception.getMessage() + "（数据集类型 " + datasetType.lower() + "）");
        }
        if (inferred != datasetType) {
            throw new IllegalArgumentException("用例字段看起来是 " + inferred.lower()
                    + "，与数据集类型 " + datasetType.lower() + " 不一致");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> tagsOf(Map<String, Object> raw) {
        Object tags = raw.get("tags");
        if (tags instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(tag -> !tag.isBlank()).toList();
        }
        return List.of();
    }

    private static String severityOf(Map<String, Object> raw) {
        String severity = stringOrNull(raw.get("severity"));
        if (severity == null) return "normal";
        String normalized = severity.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("normal") && !normalized.equals("critical")) {
            throw new IllegalArgumentException("severity 只支持 normal / critical");
        }
        return normalized;
    }

    private static List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(",")).map(String::trim)
                .filter(tag -> !tag.isEmpty()).toList();
    }

    private static String stringOrNull(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
