package com.zwx.zwxagent.web.controller;

import com.zwx.zwxagent.creative.storage.MediaStorage;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.settings.ModelCatalogService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 创作模块运行状态（C15/A35/A36）：只返回布尔就绪与本人任务计数，
 * 不包含密钥、对象键、签名 URL 或模型原文。
 */
@RestController
@RequestMapping("/ai/creative")
public class CreativeHealthController {

    private final JdbcTemplate jdbcTemplate;
    private final ModelCatalogService catalogService;
    private final MediaStorage storage;

    @Value("${creative.submissions-enabled:true}")
    private boolean submissionsEnabled;
    @Value("${creative.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    public CreativeHealthController(JdbcTemplate jdbcTemplate, ModelCatalogService catalogService,
                                    MediaStorage storage) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogService = catalogService;
        this.storage = storage;
    }

    @GetMapping("/health")
    public Map<String, Object> health(CurrentActor actor) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("submissionsEnabled", submissionsEnabled);
        body.put("schedulerEnabled", schedulerEnabled);
        body.put("storageBackend", storage.backend());
        boolean imageModelBound;
        try {
            imageModelBound = catalogService.resolveImageBinding(actor.userId()).isPresent();
        } catch (Exception exception) {
            imageModelBound = false;
        }
        body.put("imageModelBound", imageModelBound);
        body.put("database", databaseReachable());
        body.put("jobs", jobCounts(actor));
        return body;
    }

    private boolean databaseReachable() {
        try {
            Integer value = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return value != null && value == 1;
        } catch (Exception exception) {
            return false;
        }
    }

    private Map<String, Object> jobCounts(CurrentActor actor) {
        Map<String, Object> counts = new LinkedHashMap<>();
        Integer active = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM media_job
                WHERE tenant_id = ? AND user_id = ?
                  AND status IN ('QUEUED','SUBMITTING','RUNNING','RECONCILING',
                                 'CANCEL_REQUESTED','FETCHING','INGEST_FAILED')
                """, Integer.class, actor.tenantId(), actor.userId());
        Integer attention = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM media_job
                WHERE tenant_id = ? AND user_id = ? AND attention_required = TRUE
                  AND status NOT IN ('SUCCEEDED','FAILED','CANCELLED')
                """, Integer.class, actor.tenantId(), actor.userId());
        Integer ingestFailed = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM media_job
                WHERE tenant_id = ? AND user_id = ? AND status = 'INGEST_FAILED'
                """, Integer.class, actor.tenantId(), actor.userId());
        counts.put("active", active == null ? 0 : active);
        counts.put("attentionRequired", attention == null ? 0 : attention);
        counts.put("ingestFailed", ingestFailed == null ? 0 : ingestFailed);
        return counts;
    }
}
