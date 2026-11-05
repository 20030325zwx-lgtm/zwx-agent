package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.CreativeQuotaException;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.repository.MediaJobEventRepository;
import com.zwx.zwxagent.creative.repository.MediaJobRepository;
import com.zwx.zwxagent.creative.repository.MediaProviderQuotaRepository;
import com.zwx.zwxagent.platform.settings.ConfigSecretCipher;
import com.zwx.zwxagent.platform.security.CurrentActor;
import com.zwx.zwxagent.platform.security.Role;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A07/A08/A09（C04）：幂等并发、同键不同内容 409、冻结快照、事务回滚与排队上限。
 * 使用独立测试用户与专用 provider/model 行（无密钥），不访问真实供应商。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaJobServiceTest {

    private static final String TEST_USER = "creative-job-test-user";
    private static final long TEST_PROVIDER_ID = 900001L;
    private static final long TEST_MODEL_ROW_ID = 900001L;
    private static final long TEST_VIDEO_PROVIDER_ID = 900002L;
    private static final long TEST_VIDEO_MODEL_ROW_ID = 900002L;

    @Resource
    private MediaJobService jobService;

    @Resource
    private CreativeProjectService projectService;

    @Resource
    private MediaJobRepository jobRepository;

    @Resource
    private MediaProviderQuotaRepository quotaRepository;

    @MockitoSpyBean
    private MediaJobEventRepository eventRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ConfigSecretCipher secretCipher;

    private CurrentActor actor;
    private UUID projectId;
    private UUID videoProjectId;
    private UUID videoReferenceAssetId;
    private UUID jobId;

    @BeforeAll
    void setUpTestData() {
        actor = new CurrentActor(TEST_USER, "default", TEST_USER, Role.USER);
        jdbcTemplate.update("""
                        INSERT INTO model_provider (id, owner_id, name, protocol, enabled)
                        VALUES (?, ?, '测试 DashScope', 'dashscope', TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """,
                ps -> {
                    ps.setLong(1, TEST_PROVIDER_ID);
                    ps.setString(2, TEST_USER);
                });
        jdbcTemplate.update("""
                        INSERT INTO model_provider_model (id, provider_id, model_id, display_name, enabled)
                        VALUES (?, ?, 'wanx2.1-t2i-plus', '测试生图', TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """,
                ps -> {
                    ps.setLong(1, TEST_MODEL_ROW_ID);
                    ps.setLong(2, TEST_PROVIDER_ID);
                });
        jdbcTemplate.update("""
                        INSERT INTO model_binding (scope_type, owner_id, purpose, provider_id, model_row_id)
                        VALUES ('USER', ?, 'image_generation', ?, ?)
                        ON CONFLICT (scope_type, owner_id, purpose) DO NOTHING
                        """,
                ps -> {
                    ps.setString(1, TEST_USER);
                    ps.setLong(2, TEST_PROVIDER_ID);
                    ps.setLong(3, TEST_MODEL_ROW_ID);
                });
        projectId = projectService.create(actor,
                new CreativeProjectService.CreateProjectRequest("任务校验项目", null)).id();
        jdbcTemplate.update("""
                        INSERT INTO model_provider
                            (id, owner_id, name, protocol, base_url, secret_ciphertext, enabled)
                        VALUES (?, ?, '测试视频中转站', 'openai-compatible',
                                'https://video.example.test/v1', ?, TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """, TEST_VIDEO_PROVIDER_ID, TEST_USER, secretCipher.encrypt("test-video-key"));
        jdbcTemplate.update("""
                        INSERT INTO model_provider_model (id, provider_id, model_id, display_name, enabled)
                        VALUES (?, ?, 'test-seedance', '测试视频模型', TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """, TEST_VIDEO_MODEL_ROW_ID, TEST_VIDEO_PROVIDER_ID);
        jdbcTemplate.update("""
                        INSERT INTO model_binding (scope_type, owner_id, purpose, provider_id, model_row_id)
                        VALUES ('USER', ?, 'video_generation', ?, ?)
                        ON CONFLICT (scope_type, owner_id, purpose) DO UPDATE
                        SET provider_id = EXCLUDED.provider_id, model_row_id = EXCLUDED.model_row_id
                        """, TEST_USER, TEST_VIDEO_PROVIDER_ID, TEST_VIDEO_MODEL_ROW_ID);
        videoProjectId = projectService.create(actor,
                new CreativeProjectService.CreateProjectRequest("视频任务校验项目", null, "VIDEO")).id();
        videoReferenceAssetId = UUID.randomUUID();
        jdbcTemplate.update("""
                        INSERT INTO creative_asset
                            (id, project_id, tenant_id, user_id, origin, media_type, storage_backend,
                             object_key, mime_type, byte_size, sha256, display_name, status, metadata)
                        VALUES (?, ?, 'default', ?, 'UPLOAD', 'IMAGE', 'LOCAL', ?, 'image/png',
                                8, 'test-image-sha256', '首帧.png', 'READY', '{}'::jsonb)
                        """, videoReferenceAssetId, videoProjectId, TEST_USER,
                "test/video-reference/" + videoReferenceAssetId + ".png");
    }


    /**
     * 兼容同 JVM 缓存的调度器上下文：MediaJobSchedulerTest 的调度线程可能在两次删除之间
     * 再写 attempt 行，导致外键冲突。这里重试清理，最终不一致时把冲突行一并删掉。
     */
    private void deleteJobsWithRetry() throws InterruptedException {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                jdbcTemplate.update("DELETE FROM media_job WHERE user_id = ?", TEST_USER);
                return;
            } catch (org.springframework.dao.DataIntegrityViolationException exception) {
                Thread.sleep(2000);
                jdbcTemplate.update("DELETE FROM media_job_attempt WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
            }
        }
        // 兜底：只删未被引用的行，避免污染共享测试库
        jdbcTemplate.update("DELETE FROM media_job WHERE user_id = ? AND NOT EXISTS "
                + "(SELECT 1 FROM media_job_attempt a WHERE a.job_id = media_job.id)", TEST_USER);
    }

    @AfterAll
    void cleanUpTestData() throws InterruptedException {
        jdbcTemplate.update("DELETE FROM media_job_event WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        // 真实调度链会写 attempt/output/asset_ref：清理需按外键依赖顺序，否则残留行导致删除失败
        jdbcTemplate.update("DELETE FROM media_job_attempt WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job_output WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job_asset_ref WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM video_batch_item WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        deleteJobsWithRetry();
        jdbcTemplate.update("DELETE FROM creative_asset WHERE user_id = ?", TEST_USER);
        jdbcTemplate.update("DELETE FROM creative_project WHERE user_id = ?", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_model_profile WHERE model_row_id IN (?, ?)",
                TEST_MODEL_ROW_ID, TEST_VIDEO_MODEL_ROW_ID);
        jdbcTemplate.update("DELETE FROM model_binding WHERE owner_id = ? AND purpose IN ('image_generation','video_generation')",
                TEST_USER);
        jdbcTemplate.update("DELETE FROM model_provider_model WHERE provider_id IN (?, ?)",
                TEST_PROVIDER_ID, TEST_VIDEO_PROVIDER_ID);
        jdbcTemplate.update("DELETE FROM model_provider WHERE id IN (?, ?)",
                TEST_PROVIDER_ID, TEST_VIDEO_PROVIDER_ID);
        jdbcTemplate.update("DELETE FROM media_provider_quota WHERE scope_id = ?", TEST_USER);
    }

    private MediaJobService.CreateJobRequest request(String idempotentPrompt) {
        return new MediaJobService.CreateJobRequest(projectId, null, null,
                "原始：" + idempotentPrompt, idempotentPrompt, Map.of(), java.util.List.of(), null, null, null);
    }

    @Test
    @Order(1)
    void createsJobAndFreezesSnapshots() {
        var result = jobService.create(actor, "key-create-1", projectId, request("一只小鸟"));
        Assertions.assertFalse(result.reused());
        var job = jobService.getForOwner(actor, result.jobId());
        Assertions.assertEquals("QUEUED", job.status().name());
        Assertions.assertEquals("wanx2.1-t2i-plus", job.modelSnapshot().modelName());
        Assertions.assertEquals("一只小鸟", job.inputSnapshot().prompt());
        jobId = result.jobId();
    }

    @Test
    @Order(2)
    void reusesSameKeyAndRejectsChangedContent() {
        var first = jobService.create(actor, "key-reuse", projectId, request("同一内容"));
        Assertions.assertFalse(first.reused());
        // 同键同内容 → 复用（200）
        var again = jobService.create(actor, "key-reuse", projectId, request("同一内容"));
        Assertions.assertTrue(again.reused());
        Assertions.assertEquals(first.jobId(), again.jobId());
        // 同键不同内容 → 409
        Assertions.assertThrows(CreativeConflictException.class,
                () -> jobService.create(actor, "key-reuse", projectId, request("不同内容")));
        // 同键换显式模型也是新意图 → 409
        Assertions.assertThrows(CreativeConflictException.class, () -> jobService.create(actor, "key-reuse",
                projectId, new MediaJobService.CreateJobRequest(projectId, null, TEST_MODEL_ROW_ID,
                        "原始：同一内容", "同一内容", Map.of(), java.util.List.of(), null, null, null)));
    }

    @Test
    @Order(3)
    void twentyConcurrentSameKeyRequestsCreateSingleJob() throws Exception {
        String key = "key-concurrent-20";
        int threads = 20;
        var latch = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(threads);
        var futures = new ArrayList<Future<MediaJobService.CreateJobResult>>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit((Callable<MediaJobService.CreateJobResult>) () -> {
                latch.await();
                return jobService.create(actor, key, projectId, request("并发内容"));
            }));
        }
        latch.countDown();
        var results = new ArrayList<MediaJobService.CreateJobResult>();
        for (Future<MediaJobService.CreateJobResult> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();
        Assertions.assertEquals(threads, results.size());
        UUID sharedJobId = results.get(0).jobId();
        Assertions.assertTrue(results.stream().allMatch(r -> r.jobId().equals(sharedJobId)));
        Assertions.assertEquals(1, results.stream().filter(r -> !r.reused()).count(),
                "恰好一次新建，其余复用");
        Integer jobCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_job WHERE idempotency_key = ?", Integer.class, key);
        Assertions.assertEquals(1, jobCount);
        Integer eventCount = jdbcTemplate.queryForObject("""
                        SELECT count(*) FROM media_job_event e JOIN media_job j ON j.id = e.job_id
                        WHERE j.idempotency_key = ? AND e.event_type = 'JOB_CREATED'
                        """,
                Integer.class, key);
        Assertions.assertEquals(1, eventCount);
    }

    @Test
    @Order(4)
    void eventFailureRollsBackWholeTransaction() {
        String key = "key-rollback";
        Mockito.doThrow(new IllegalStateException("模拟事件写入故障")).when(eventRepository)
                .append(Mockito.any(), Mockito.anyString(), Mockito.anyString(),
                        Mockito.anyString(), Mockito.anyString());
        try {
            Assertions.assertThrows(IllegalStateException.class,
                    () -> jobService.create(actor, key, projectId, request("回滚校验")));
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM media_job WHERE idempotency_key = ?", Integer.class, key);
            Assertions.assertEquals(0, count, "事务必须整体回滚，不留半成品 job");
            Mockito.reset(eventRepository);
            // 故障解除后同键可正常受理
            var result = jobService.create(actor, key, projectId, request("回滚校验"));
            Assertions.assertFalse(result.reused());
        } finally {
            Mockito.reset(eventRepository);
        }
    }

    @Test
    @Order(5)
    void queuedQuotaReturns429WithoutHalfCreatedJob() {
        quotaRepository.upsert("USER", TEST_USER, 4, 1);
        try {
            // 已有 1 条 QUEUED（order 1 创建），再创建即达上限
            Assertions.assertThrows(CreativeQuotaException.class,
                    () -> jobService.create(actor, "key-quota", projectId, request("超额")));
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM media_job WHERE idempotency_key = 'key-quota'", Integer.class);
            Assertions.assertEquals(0, count, "超限请求不应创建任务");
        } finally {
            quotaRepository.delete("USER", TEST_USER);
        }
    }

    @Test
    @Order(6)
    void getForOwnerRejectsForeignUser() {
        var stranger = new CurrentActor("creative-job-stranger", "default", "stranger", Role.USER);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> jobService.getForOwner(stranger, jobId));
    }

    @Test
    @Order(7)
    void trustedSourceIsPersistedAndVisible() {
        var result = jobService.create(actor, "key-source-agent", projectId, request("来源追踪"),
                com.zwx.zwxagent.creative.domain.JobSourceType.AGENT, "run-123", null);
        var job = jobService.getForOwner(actor, result.jobId());
        Assertions.assertEquals(com.zwx.zwxagent.creative.domain.JobSourceType.AGENT, job.sourceType());
        Assertions.assertEquals("run-123", job.sourceRunId());
        Assertions.assertNull(job.sourceNodeId());

        var workflow = jobService.create(actor, "key-source-workflow", projectId, request("工作流来源"),
                com.zwx.zwxagent.creative.domain.JobSourceType.WORKFLOW, "run-9", "node-gen");
        var workflowJob = jobService.getForOwner(actor, workflow.jobId());
        Assertions.assertEquals(com.zwx.zwxagent.creative.domain.JobSourceType.WORKFLOW,
                workflowJob.sourceType());
        Assertions.assertEquals("node-gen", workflowJob.sourceNodeId());
    }

    @Test
    @Order(8)
    void eventCursorBeyondMaxRequestsReset() {
        var job = jobService.create(actor, "key-event-reset", projectId, request("事件重置"));
        var normal = jobService.eventPage(actor, job.jobId(), 0, 100);
        Assertions.assertFalse(normal.reset());
        Assertions.assertEquals(1, normal.events().size(), "初始应只有 JOB_CREATED");
        Assertions.assertEquals(normal.nextSeq(), normal.events().getFirst().seq());

        var reset = jobService.eventPage(actor, job.jobId(), normal.nextSeq() + 50, 100);
        Assertions.assertTrue(reset.reset(), "越界游标必须要求客户端重置");
        Assertions.assertTrue(reset.events().isEmpty());
        Assertions.assertEquals(normal.nextSeq(), reset.nextSeq());
        Assertions.assertNotNull(reset.snapshot(), "重置应附带最新任务快照");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> jobService.eventPage(actor, job.jobId(), -1, 100));
    }

    @Test
    @Order(9)
    void retryRequeuesSameJobAndPreservesAttemptHistory() {
        var created = jobService.create(actor, "key-retry-in-place", projectId, request("原任务重试"));
        jdbcTemplate.update("""
                UPDATE media_job SET status = 'FAILED', error_code = 'PRE_SUBMIT_FAILED',
                    error_summary = '调用前失败', finished_at = now(), next_action_at = now() + interval '1 hour'
                WHERE id = ?
                """, created.jobId());
        UUID preservedAttemptId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO media_job_attempt (id, job_id, attempt_no, action, outcome, started_at, finished_at)
                VALUES (?, ?, 1, 'SUBMIT', 'REJECTED', now(), now())
                """, preservedAttemptId, created.jobId());
        Integer beforeCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_job WHERE user_id = ?", Integer.class, TEST_USER);

        var retried = jobService.retry(actor, created.jobId(), false);

        Assertions.assertEquals(created.jobId(), retried.jobId(), "重试必须复用原任务 ID");
        Assertions.assertEquals("QUEUED", retried.status());
        Integer afterCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_job WHERE user_id = ?", Integer.class, TEST_USER);
        Assertions.assertEquals(beforeCount, afterCount, "重试不得新建 media_job");
        Integer preservedAttempts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_job_attempt WHERE id = ? AND job_id = ?",
                Integer.class, preservedAttemptId, created.jobId());
        Assertions.assertEquals(1, preservedAttempts, "既有 attempt 历史必须保留");
        Integer retryEvents = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM media_job_event WHERE job_id = ? AND event_type = 'RETRY_QUEUED'
                """, Integer.class, created.jobId());
        Assertions.assertEquals(1, retryEvents);
    }

    @Test
    @Order(10)
    void imageToVideoAcceptsPromptAndOneReadyImage() {
        var missingReference = new MediaJobService.CreateJobRequest(videoProjectId, "image_to_video", null,
                "让画面动起来", "镜头缓慢推进，人物转头看向镜头", Map.of("seconds", "5"),
                List.of(), null, null, null);
        Assertions.assertThrows(CreativeException.class,
                () -> jobService.create(actor, "key-video-missing-reference", videoProjectId, missingReference));

        var request = new MediaJobService.CreateJobRequest(videoProjectId, "image_to_video", null,
                "让画面动起来", "镜头缓慢推进，人物转头看向镜头", Map.of("seconds", "5"),
                List.of(videoReferenceAssetId), null, null, null);
        var created = jobService.create(actor, "key-image-to-video", videoProjectId, request);
        var job = jobService.getForOwner(actor, created.jobId());

        Assertions.assertEquals("QUEUED", created.status());
        Assertions.assertEquals("image_to_video", job.inputSnapshot().operation());
        Assertions.assertEquals(List.of(videoReferenceAssetId), job.inputSnapshot().referenceAssetIds());
        Assertions.assertEquals("5", job.inputSnapshot().parameters().get("seconds"));
        Assertions.assertEquals("openai-video", job.modelSnapshot().adapterKey());
    }
}
