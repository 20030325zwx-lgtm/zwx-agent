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
    private final com.zwx.zwxagent.creative.service.MediaModelResolver mediaModelResolver;

    @Value("${creative.submissions-enabled:true}")
    private boolean submissionsEnabled;
    @Value("${creative.scheduler.enabled:false}")
    private boolean schedulerEnabled;

    public CreativeHealthController(JdbcTemplate jdbcTemplate, ModelCatalogService catalogService,
                                    MediaStorage storage,
                                    com.zwx.zwxagent.creative.service.MediaModelResolver mediaModelResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogService = catalogService;
        this.storage = storage;
        this.mediaModelResolver = mediaModelResolver;
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
        body.put("videoOperations", videoOperations(actor));
        body.put("database", databaseReachable());
        body.put("jobs", jobCounts(actor));
        return body;
    }

    /**
     * 当前绑定的视频模型支持哪些操作（如 lk888 gk-video 仅图生视频）。
     * 只返回布尔、不回退其它模型；解析失败按不支持处理，避免 UI 出现点了才被拒的选项。
     */
    private Map<String, Boolean> videoOperations(CurrentActor actor) {
        Map<String, Boolean> operations = new LinkedHashMap<>();
        for (String operation : java.util.List.of("text_to_video", "image_to_video")) {
            boolean supported;
            try {
                mediaModelResolver.resolve(actor.userId(), null, operation);
                supported = true;
            } catch (Exception exception) {
                supported = false;
            }
            operations.put(operation, supported);
        }
        return operations;
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
