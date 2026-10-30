package com.zwx.zwxagent.creative.job;

import com.zwx.zwxagent.creative.domain.CreativeProjectRecord;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.domain.MediaJobStatus;
import com.zwx.zwxagent.creative.domain.ModelSnapshot;
import com.zwx.zwxagent.creative.provider.MediaProviderAdapter;
import com.zwx.zwxagent.creative.repository.CreativeProjectRepository;
import com.zwx.zwxagent.creative.repository.MediaJobRepository;
import com.zwx.zwxagent.creative.repository.MediaModelProfileRepository;
import com.zwx.zwxagent.creative.repository.MediaProviderQuotaRepository;
import com.zwx.zwxagent.creative.provider.MockMediaProviderAdapter;
import com.zwx.zwxagent.creative.service.CreativeProjectService;
import com.zwx.zwxagent.creative.service.MediaJobService;
import com.zwx.zwxagent.creative.service.MediaJobService;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A10–A13（C05）、A19–A20（C07）：调度认领与配额互锁、租约 fencing、重启恢复不重发提交、
 * 提交结果不确定进入 RECONCILING、入库失败可重试且不重复提交/不重复素材。
 * 全部使用进程内 Mock adapter + 本地媒体目录，不发起真实模型调用。
 */
@SpringBootTest(properties = {
        "creative.ingest.backend=storage",
        "creative.storage.backend=local",
        "creative.storage.local-dir=target/creative-test-media",
        "creative.download.allow-local=true"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MediaJobSchedulerTest {

    private static final String TEST_USER = "creative-scheduler-test-user";
    private static final long TEST_PROVIDER_ID = 900101L;
    private static final long TEST_MODEL_ROW_ID = 900101L;

    @Resource
    private MediaJobScheduler scheduler;

    @Resource
    private MediaJobService jobService;

    @Resource
    private CreativeProjectService projectService;

    @Resource
    private MediaJobRepository jobRepository;

    @Resource
    private MediaModelProfileRepository profileRepository;

    @Resource
    private MediaProviderQuotaRepository quotaRepository;

    @Resource
    private MockMediaProviderAdapter mockAdapter;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.zwx.zwxagent.creative.storage.LocalMediaStorage localStorage;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private com.zwx.zwxagent.creative.repository.CreativeAssetRepository assetRepository;

    private CurrentActor actor;
    private UUID projectId;

    @org.junit.jupiter.api.BeforeEach
    void cleanLeftoverJobs() {
        // 避免前序用例遗留的 RESERVED 任务占满用户/全局额度
        jdbcTemplate.update("DELETE FROM media_job_event WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job_output WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job_attempt WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job WHERE user_id = ?", TEST_USER);
    }

    @BeforeAll
    void setUpTestData() {
        actor = new CurrentActor(TEST_USER, "default", TEST_USER, Role.USER);
        jdbcTemplate.update("""
                        INSERT INTO model_provider (id, owner_id, name, protocol, enabled)
                        VALUES (?, ?, '调度测试 DashScope', 'dashscope', TRUE)
                        ON CONFLICT (id) DO NOTHING
                        """,
                ps -> {
                    ps.setLong(1, TEST_PROVIDER_ID);
                    ps.setString(2, TEST_USER);
                });
        jdbcTemplate.update("""
                        INSERT INTO model_provider_model (id, provider_id, model_id, display_name, enabled)
                        VALUES (?, ?, 'wanx2.1-t2i-plus', '调度测试生图', TRUE)
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
        // 调度测试统一走 Mock 适配器
        profileRepository.upsert(TEST_MODEL_ROW_ID, "mock-text2image", "1",
                Map.of("text_to_image", true),
                Map.of("operation", "text_to_image", "parameters", List.of()), null, null);
        projectId = projectService.create(actor,
                new CreativeProjectService.CreateProjectRequest("调度校验项目", null)).id();
    }

    @AfterAll
    void cleanUpTestData() {
        jdbcTemplate.update("DELETE FROM media_job_event WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job_output WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job_attempt WHERE job_id IN (SELECT id FROM media_job WHERE user_id = ?)", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_job WHERE user_id = ?", TEST_USER);
        jdbcTemplate.update("DELETE FROM media_model_profile WHERE model_row_id = ?", TEST_MODEL_ROW_ID);
        jdbcTemplate.update("DELETE FROM model_binding WHERE owner_id = ? AND purpose = 'image_generation'", TEST_USER);
        jdbcTemplate.update("DELETE FROM model_provider_model WHERE provider_id = ?", TEST_PROVIDER_ID);
        jdbcTemplate.update("DELETE FROM model_provider WHERE id = ?", TEST_PROVIDER_ID);
        jdbcTemplate.update("DELETE FROM media_provider_quota WHERE scope_id = ?", TEST_USER);
    }

    private MediaJobService.CreateJobRequest request(String prompt) {
        return new MediaJobService.CreateJobRequest(projectId, null, null,
                "原始：" + prompt, prompt, Map.of(), java.util.List.of(), null, null, null);
    }

    private MediaJobRecord createQueued(String key, String prompt) {
        jobService.create(actor, key, projectId, request(prompt));
        return jobRepository.findByIdempotencyKey("default", TEST_USER, key).orElseThrow();
    }

    private void pushNextActionNow(UUID jobId) {
        jdbcTemplate.update("""
                UPDATE media_job SET next_action_at = now(), lease_until = now() - interval '1 second'
                WHERE id = ?
                """, jobId);
    }

    @Test
    @Order(1)
    void userInflightQuotaKeepsExcessJobsQueued() {
        mockAdapter.reset();
        mockAdapter.setDefaultQueryScript(
                new MockMediaProviderAdapter.QueryScript(1000, MockMediaProviderAdapter.QueryState.SUCCEEDED,
                        List.of(), null));
        quotaRepository.upsert("USER", TEST_USER, 1, 20);
        quotaRepository.upsert("PROVIDER", String.valueOf(TEST_PROVIDER_ID), 10, 20);
        var job1 = createQueued("sched-q1", "内容一");
        var job2 = createQueued("sched-q2", "内容二");
        var job3 = createQueued("sched-q3", "内容三");
        try {
            scheduler.tick();
            Assertions.assertEquals(MediaJobStatus.RUNNING, jobRepository.findById(job1.id()).orElseThrow().status());
            Assertions.assertEquals(MediaJobStatus.QUEUED, jobRepository.findById(job2.id()).orElseThrow().status());
            Assertions.assertEquals(MediaJobStatus.QUEUED, jobRepository.findById(job3.id()).orElseThrow().status());
            Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + job1.id() + "-1"));
            Assertions.assertEquals(0, mockAdapter.submitCount("sub-" + job2.id() + "-1"));
        } finally {
            quotaRepository.delete("USER", TEST_USER);
            quotaRepository.delete("PROVIDER", String.valueOf(TEST_PROVIDER_ID));
        }
    }

    @Test
    @Order(2)
    void twoSchedulerInstancesRespectUserQuotaUnderConcurrency() {
        mockAdapter.reset();
        mockAdapter.setDefaultQueryScript(
                new MockMediaProviderAdapter.QueryScript(1000, MockMediaProviderAdapter.QueryState.SUCCEEDED,
                        List.of(), null));
        quotaRepository.upsert("USER", TEST_USER, 1, 20);
        var jobs = new ArrayList<MediaJobRecord>();
        for (int i = 0; i < 5; i++) {
            jobs.add(createQueued("sched-dual-" + i, "并发任务 " + i));
        }
        // 两个调度实例并发认领：USER 在途上限 1，多出的任务必须留在 QUEUED
        Thread first = new Thread(() -> scheduler.tick());
        Thread second = new Thread(() -> scheduler.tick());
        first.start();
        second.start();
        try {
            first.join();
            second.join();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        long running = jobs.stream().filter(j -> jobRepository.findById(j.id()).orElseThrow().status()
                == MediaJobStatus.RUNNING).count();
        Assertions.assertEquals(1, running, "用户在途上限 1：只能有一个任务进入 RUNNING");
        for (var job : jobs) {
            var record = jobRepository.findById(job.id()).orElseThrow();
            if (record.status() == MediaJobStatus.RUNNING) continue;
            Assertions.assertEquals(MediaJobStatus.QUEUED, record.status());
        }
    }

    @Test
    @Order(3)
    void oldWorkerFencedWriteIsRejectedAfterReclaim() {
        mockAdapter.reset();
        mockAdapter.setDefaultQueryScript(
                new MockMediaProviderAdapter.QueryScript(1000, MockMediaProviderAdapter.QueryState.SUCCEEDED, List.of(), null));
        var job = createQueued("sched-fence", "围栏校验");
        long versionBefore = job.version();
        long epochBefore = job.leaseEpoch();
        scheduler.tick();
        var claimed = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertTrue(claimed.leaseEpoch() > epochBefore, "认领必须推进租约 epoch");
        // 旧 worker 用旧 version/epoch 回写：必须被拒
        Assertions.assertFalse(jobRepository.updateStatusFenced(job.id(),
                versionBefore, epochBefore, MediaJobStatus.RUNNING, null, null));
        // 新 epoch 正常推进
        Assertions.assertTrue(jobRepository.updateStatusFenced(job.id(),
                claimed.version(), claimed.leaseEpoch(), MediaJobStatus.RUNNING,
                java.time.Instant.now().plusMillis(100), null));
    }

    @Test
    @Order(4)
    void recoveryResumesQueryWithoutResubmitting() {
        mockAdapter.reset();
        mockAdapter.setDefaultQueryScript(
                new MockMediaProviderAdapter.QueryScript(0, MockMediaProviderAdapter.QueryState.SUCCEEDED, List.of(), null));
        var job = createQueued("sched-recover", "重启恢复");
        int handled = scheduler.tick();
        var running = jobRepository.findById(job.id()).orElseThrow();
        System.out.println("[probe] handled=" + handled + " status=" + running.status()
                + " attention=" + running.attentionRequired() + " blocked=" + running.blockedReason());
        Assertions.assertEquals(MediaJobStatus.RUNNING, running.status());
        Assertions.assertNotNull(running.providerTaskId());
        Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + running.id() + "-1"));
        // 模拟进程重启：租约过期
        jdbcTemplate.update("UPDATE media_job SET lease_until = now() - interval '1 second' WHERE id = ?", job.id());
        scheduler.recover();
        pushNextActionNow(job.id());
        scheduler.tick();
        var done = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertEquals(MediaJobStatus.SUCCEEDED, done.status());
        Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + done.id() + "-1"),
                "恢复后不得重新提交");
    }

    @Test
    @Order(5)
    void uncertainSubmissionEntersReconcilingWithoutResubmit() {
        mockAdapter.reset();
        String key = "sched-uncertain";
        mockAdapter.enqueueSubmitResult("*",
                MockMediaProviderAdapter.SubmitResult.unknown("mock 断连"));
        var job = createQueued(key, "不确定提交");
        scheduler.tick();
        var reconciling = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertEquals(MediaJobStatus.RECONCILING, reconciling.status());
        Assertions.assertNull(reconciling.providerTaskId());
        Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + job.id() + "-1"));
        // 无对账依据（DashScope 无幂等键查询）：保持 RECONCILING 并标记关注，绝不重发
        pushNextActionNow(job.id());
        scheduler.tick();
        var still = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertEquals(MediaJobStatus.RECONCILING, still.status());
        Assertions.assertNull(still.providerTaskId());
        Assertions.assertTrue(still.attentionRequired());
        Assertions.assertEquals(1, mockAdapter.submitCount());
    }

    // ------------------------------------------------------------ C07 入库

    private MediaJobRecord submitAndRun(String key) {
        var job = createQueued(key, "入库用例 " + key);
        scheduler.tick();
        pushNextActionNow(job.id());
        return jobRepository.findById(job.id()).orElseThrow();
    }

    private String objectKeyOf(UUID jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT object_key FROM media_job_output WHERE job_id = ? ORDER BY output_index LIMIT 1",
                String.class, jobId);
    }

    private int assetCount(UUID jobId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM creative_asset WHERE source_job_id = ?", Integer.class, jobId);
        return count == null ? 0 : count;
    }

    @Test
    @Order(6)
    void ingestFailureRetriesWithoutSecondSubmit() {
        mockAdapter.reset();
        mockAdapter.setDefaultQueryScript(
                new MockMediaProviderAdapter.QueryScript(0, MockMediaProviderAdapter.QueryState.SUCCEEDED,
                        List.of(), null));
        var job = submitAndRun("sched-a19");
        // 第一次对象写入失败
        org.mockito.Mockito.doThrow(new IllegalStateException("模拟磁盘写失败"))
                .doCallRealMethod()
                .when(localStorage).put(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(java.io.InputStream.class),
                        org.mockito.ArgumentMatchers.anyLong());
        scheduler.tick();
        var failed = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertEquals(MediaJobStatus.INGEST_FAILED, failed.status());
        Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + job.id() + "-1"));
        Assertions.assertEquals(0, assetCount(job.id()), "入库失败不得留下正式素材");

        // 恢复：INGEST_FAILED → FETCHING → SUCCEEDED，且不重新提交
        pushNextActionNow(job.id());
        scheduler.tick();
        var done = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertEquals(MediaJobStatus.SUCCEEDED, done.status());
        Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + job.id() + "-1"),
                "入库重试只能重新下载，不能重发提交");
        Assertions.assertEquals(1, assetCount(job.id()));
        Assertions.assertTrue(localStorage.size(objectKeyOf(job.id())) > 0, "对象应已落盘");
    }

    @Test
    @Order(7)
    void objectWrittenButAssetInsertFailsReusesSameSlot() {
        mockAdapter.reset();
        mockAdapter.setDefaultQueryScript(
                new MockMediaProviderAdapter.QueryScript(0, MockMediaProviderAdapter.QueryState.SUCCEEDED,
                        List.of(), null));
        var job = submitAndRun("sched-a20-db");
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("模拟数据库失败"))
                .doCallRealMethod()
                .when(assetRepository).insert(org.mockito.ArgumentMatchers.any());
        scheduler.tick();
        var failed = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertEquals(MediaJobStatus.INGEST_FAILED, failed.status());
        // 对象已写成功但数据库失败：恢复后复用同一槽位/对象键
        String key = objectKeyOf(job.id());
        Assertions.assertTrue(localStorage.size(key) > 0);
        pushNextActionNow(job.id());
        scheduler.tick();
        var done = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertEquals(MediaJobStatus.SUCCEEDED, done.status());
        Assertions.assertEquals(1, assetCount(job.id()), "不得产生重复素材");
        Assertions.assertEquals(key, objectKeyOf(job.id()), "复用原 objectKey");
        Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + job.id() + "-1"));
    }

    @Test
    @Order(8)
    void expiredResultUrlStaysIngestFailedWithoutResubmit() {
        mockAdapter.reset();
        mockAdapter.setDefaultQueryScript(
                new MockMediaProviderAdapter.QueryScript(0, MockMediaProviderAdapter.QueryState.SUCCEEDED,
                        List.of(MediaProviderAdapter.Output.ofUrl("http://127.0.0.1:1/expired.png")), null));
        var job = submitAndRun("sched-a20-url");
        scheduler.tick();
        var failed = jobRepository.findById(job.id()).orElseThrow();
        Assertions.assertEquals(MediaJobStatus.INGEST_FAILED, failed.status(),
                "结果 URL 过期/不可达时保持可重试的入库失败，不重生图片");
        Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + job.id() + "-1"));
        Assertions.assertEquals(0, assetCount(job.id()));
        // 再次调度仍只是重新查询/下载，不产生第二次提交
        pushNextActionNow(job.id());
        scheduler.tick();
        Assertions.assertEquals(1, mockAdapter.submitCount("sub-" + job.id() + "-1"));
    }

}
