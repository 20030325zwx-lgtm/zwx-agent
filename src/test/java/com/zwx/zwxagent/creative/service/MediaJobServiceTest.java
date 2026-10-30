package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeConflictException;
import com.zwx.zwxagent.creative.domain.CreativeQuotaException;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.repository.MediaJobEventRepository;
import com.zwx.zwxagent.creative.repository.MediaJobRepository;
import com.zwx.zwxagent.creative.repository.MediaProviderQuotaRepository;
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

    private CurrentActor actor;
    private UUID projectId;
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
    }

    @AfterAll
    void cleanUpTestData() {
        jdbcTemplate.update("DELETE FROM media_job_event WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job WHERE user_id = ?", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_model_profile WHERE model_row_id = ?", TEST_MODEL_ROW_ID);
        jdbcTemplate.update("DELETE FROM model_binding WHERE owner_id = ? AND purpose = 'image_generation'", TEST_USER);
        jdbcTemplate.update("DELETE FROM model_provider_model WHERE provider_id = ?", TEST_PROVIDER_ID);
        jdbcTemplate.update("DELETE FROM model_provider WHERE id = ?", TEST_PROVIDER_ID);
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
}
