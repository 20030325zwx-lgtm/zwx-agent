package com.zwx.aster.eval.service;

import com.zwx.aster.eval.domain.EvalJson;
import com.zwx.aster.eval.domain.TargetSpec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prompt 资产库：key@version 寻址，版本发布后不可变。
 * 它补的是「版本四元组」里唯一缺失的 prompt 维度——没有它，PROMPT 评测只能测一段手抄的字符串。
 */
@Service
public class PromptAssetService {

    private final JdbcTemplate jdbcTemplate;

    public PromptAssetService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID create(String tenantId, String assetKey, String name, String description) {
        String key = normalizeKey(assetKey);
        String displayName = name == null || name.isBlank() ? key : name.trim();
        UUID id = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO platform.prompt_asset (id, tenant_id, asset_key, name, description)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, tenantId, key, displayName, description);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new IllegalArgumentException("Prompt 资产已存在：" + key);
        }
        return id;
    }

    public List<Map<String, Object>> list(String tenantId) {
        return jdbcTemplate.queryForList("""
                SELECT a.id::text, a.asset_key, a.name, a.description, a.created_at,
                       COALESCE(v.latest_version, 0) AS latest_version,
                       COALESCE(v.version_count, 0) AS version_count
                FROM platform.prompt_asset a
                LEFT JOIN (SELECT asset_id, MAX(version_no) AS latest_version, COUNT(*) AS version_count
                           FROM platform.prompt_version GROUP BY asset_id) v ON v.asset_id = a.id
                WHERE a.tenant_id = ?
                ORDER BY a.created_at DESC
                """, tenantId);
    }

    public Map<String, Object> get(String tenantId, UUID id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id::text, asset_key, name, description, created_at FROM platform.prompt_asset "
                        + "WHERE id = ? AND tenant_id = ?", id, tenantId);
        if (rows.isEmpty()) throw new IllegalArgumentException("Prompt 资产不存在：" + id);
        Map<String, Object> asset = new LinkedHashMap<>(rows.get(0));
        asset.put("versions", jdbcTemplate.queryForList("""
                SELECT version_no, notes, published_at, length(COALESCE(system, '')) AS system_chars,
                       left(COALESCE(system, ''), 400) AS system_preview
                FROM platform.prompt_version WHERE asset_id = ? ORDER BY version_no DESC
                """, id));
        return asset;
    }

    /** 发布新版本（版本号自增，内容不可变）。 */
    public int publishVersion(String tenantId, UUID assetId, String system, String userTemplate,
                              List<String> params, String notes) {
        Map<String, Object> asset = get(tenantId, assetId);
        if (system == null && userTemplate == null) {
            throw new IllegalArgumentException("system 与 userTemplate 至少填一个");
        }
        Integer latest = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM platform.prompt_version WHERE asset_id = ?",
                Integer.class, assetId);
        int next = (latest == null ? 0 : latest) + 1;
        jdbcTemplate.update("""
                        INSERT INTO platform.prompt_version
                            (id, tenant_id, asset_id, version_no, system, user_template, params, notes)
                        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                        """,
                UUID.randomUUID(), tenantId, assetId, next, system, userTemplate,
                EvalJson.toJson(params == null ? List.of() : params), notes);
        jdbcTemplate.update("UPDATE platform.prompt_asset SET updated_at = now() WHERE id = ?", assetId);
        return next;
    }

    /** 解析 key@version（version 为空取最新）。 */
    public ResolvedPrompt resolve(String tenantId, TargetSpec.PromptRef ref) {
        Map<String, Object> asset = jdbcTemplate.queryForMap(
                "SELECT id::text, asset_key FROM platform.prompt_asset WHERE tenant_id = ? AND asset_key = ?",
                tenantId, ref.assetKey());
        UUID assetId = UUID.fromString(String.valueOf(asset.get("id")));
        Map<String, Object> version;
        if (ref.versionNo() == null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT version_no, system FROM platform.prompt_version
                    WHERE asset_id = ? ORDER BY version_no DESC LIMIT 1
                    """, assetId);
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Prompt 资产 " + ref.assetKey() + " 还没有已发布版本");
            }
            version = rows.get(0);
        } else {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT version_no, system FROM platform.prompt_version
                    WHERE asset_id = ? AND version_no = ?
                    """, assetId, ref.versionNo());
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("Prompt 资产 " + ref.assetKey() + " 不存在 v" + ref.versionNo());
            }
            version = rows.get(0);
        }
        return new ResolvedPrompt(ref.assetKey(), ((Number) version.get("version_no")).intValue(),
                version.get("system") == null ? null : String.valueOf(version.get("system")));
    }

    public record ResolvedPrompt(String assetKey, int versionNo, String system) {
    }

    private static String normalizeKey(String assetKey) {
        if (assetKey == null || assetKey.isBlank()) {
            throw new IllegalArgumentException("assetKey 不能为空");
        }
        String key = assetKey.trim().toLowerCase(java.util.Locale.ROOT);
        if (!key.matches("[a-z0-9][a-z0-9._-]{1,63}")) {
            throw new IllegalArgumentException("assetKey 只允许小写字母/数字/._-，长度 2-64：" + assetKey);
        }
        return key;
    }
}
