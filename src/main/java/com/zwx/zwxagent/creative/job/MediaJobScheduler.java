package com.zwx.zwxagent.creative.job;

import com.zwx.zwxagent.creative.asset.AssetIngestService;
import com.zwx.zwxagent.creative.domain.AttemptAction;
import com.zwx.zwxagent.creative.domain.AttemptOutcome;
import com.zwx.zwxagent.creative.domain.CreativeException;
import com.zwx.zwxagent.creative.domain.InputSnapshot;
import com.zwx.zwxagent.creative.domain.MediaJobOutputRecord;
import com.zwx.zwxagent.creative.domain.MediaJobRecord;
import com.zwx.zwxagent.creative.domain.MediaJobStatus;
import com.zwx.zwxagent.creative.domain.ModelSnapshot;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import com.zwx.zwxagent.creative.domain.ResolvedMediaModel;
import com.zwx.zwxagent.creative.provider.MediaProviderAdapter;
import com.zwx.zwxagent.creative.repository.CreativeAssetRepository;
import com.zwx.zwxagent.creative.repository.MediaJobEventRepository;
import com.zwx.zwxagent.creative.repository.MediaJobOutputRepository;
import com.zwx.zwxagent.creative.repository.MediaProviderQuotaRepository;
import com.zwx.zwxagent.creative.repository.MediaJobRepository;
import com.zwx.zwxagent.creative.support.CreativeJson;
import com.zwx.zwxagent.platform.settings.ConfigSecretCipher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 媒体任务调度器（C05）：认领到期任务（SKIP LOCKED + 租约 epoch），
 * 提交/查询在事务外调用供应商，结果以 version+epoch fencing 写回；
 * 配额按 GLOBAL→PROVIDER→USER 顺序加锁检查。
 * 默认不启动后台循环（creative.scheduler.enabled），测试直接调用 tick()。
 */
@Component
public class MediaJobScheduler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(MediaJobScheduler.class);


    private static final String EVENT_STATUS_CHANGED = "STATUS_CHANGED";
    private static final List<MediaJobStatus> ACTIVE = MediaJobStatus.active()
            .stream().filter(s -> s != MediaJobStatus.QUEUED).toList();

    private final MediaJobRepository jobRepository;
    private final MediaJobEventRepository eventRepository;
    private final MediaJobOutputRepository outputRepository;
    private final MediaProviderQuotaRepository quotaRepository;
    private final AssetIngestService ingestService;
    private final ConfigSecretCipher secretCipher;
    private final TransactionTemplate transactionTemplate;
    private final Map<String, MediaProviderAdapter> adapters;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Value("${creative.scheduler.enabled:false}")
    private boolean enabled;
    @Value("${creative.scheduler.interval-ms:1000}")
    private long intervalMs;
    @Value("${creative.scheduler.batch-size:10}")
    private int batchSize;
    @Value("${creative.scheduler.lease-seconds:60}")
    private int leaseSeconds;
    @Value("${creative.scheduler.query-backoff-ms:3000}")
    private long queryBackoffMs;
    @Value("${creative.scheduler.query-backoff-max-ms:15000}")
    private long queryBackoffMaxMs;
    @Value("${creative.scheduler.reconcile-delay-ms:60000}")
    private long reconcileDelayMs;
    @Value("${creative.scheduler.user-max-inflight:2}")
    private int userMaxInflight;
    @Value("${creative.scheduler.quota-queries-before-attention:40}")
    private int quotaQueriesBeforeAttention;
    @Value("${creative.download.allow-local:false}")
    private boolean downloadAllowLocal;
    @Value("${creative.download.max-bytes:20971520}")
    private long downloadMaxBytes;

    private ScheduledExecutorService loop;
    private final String workerId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    public MediaJobScheduler(MediaJobRepository jobRepository, MediaJobEventRepository eventRepository,
                             MediaJobOutputRepository outputRepository,
                             MediaProviderQuotaRepository quotaRepository,
                             AssetIngestService ingestService, ConfigSecretCipher secretCipher,
                             TransactionTemplate transactionTemplate, List<MediaProviderAdapter> adapters,
                             org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                             io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.eventRepository = eventRepository;
        this.outputRepository = outputRepository;
        this.quotaRepository = quotaRepository;
        this.ingestService = ingestService;
        this.secretCipher = secretCipher;
        this.transactionTemplate = transactionTemplate;
        this.adapters = adapters.stream().collect(Collectors.toMap(
                MediaProviderAdapter::key, Function.identity(), (a, b) -> a));
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void start() {
        if (!enabled) return;
        // 启动恢复：有 taskId 的任务继续查询；提交结果不明的进入 RECONCILING，绝不重发
        try {
            recover();
        } catch (Exception exception) {
            log.warn("媒体任务启动恢复失败：{}", exception.getMessage(), exception);
        }
        loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "media-job-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        loop.scheduleWithFixedDelay(this::safeTick, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (loop != null) loop.shutdownNow();
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception exception) {
            // 单轮失败不影响下一轮，但必须留下可定位日志
            log.warn("媒体调度轮次失败：{}", exception.getMessage(), exception);
        }
    }

    /** 认领并处理一批到期任务，返回处理数量。测试直接调用以获得确定性。 */
    public int tick() {
        List<UUID> claimed = transactionTemplate.execute(status -> {
            var due = jobRepository.findDueForClaim(Instant.now(), batchSize);
            for (var job : due) {
                jobRepository.claim(job.id(), workerId, Instant.now().plusSeconds(leaseSeconds));
            }
            return due.stream().map(MediaJobRecord::id).toList();
        });
        if (claimed == null || claimed.isEmpty()) return 0;
        int handled = 0;
        for (UUID id : claimed) {
            try {
                var job = jobRepository.findById(id).orElse(null);
                if (job == null) continue;
                handleClaimed(job);
                handled++;
            } finally {
                // 动作结束即释放租约；下一次由 next_action_at 控制，崩溃时租约仍会自然过期
                jobRepository.releaseLease(id);
            }
        }
        return handled;
    }

    private void handleClaimed(MediaJobRecord job) {
        try {
            switch (job.status()) {
                case QUEUED -> handleSubmit(job);
                case RUNNING, RECONCILING, FETCHING, INGEST_FAILED -> handleQueryOrIngest(job);
                case CANCEL_REQUESTED -> handleCancelRequested(job);
                case SUBMITTING -> { /* 提交结果不明：由恢复器转 RECONCILING，不重复提交 */ }
                default -> { /* 终态不应被认领 */ }
            }
        } catch (Exception exception) {
            log.warn("媒体任务处理失败：job={} status={}；{}", job.id(), job.status(),
                    exception.getMessage(), exception);
            throw exception;
        }
    }

    // ---------------------------------------------------------------- 提交

    private void handleSubmit(MediaJobRecord job) {
        long version = job.version();
        long epoch = job.leaseEpoch();
        String submissionKey = "sub-" + job.id() + "-" + epoch;
        // 配额与占位必须在同一事务内完成：行锁 → 计数 → 置 SUBMITTING+inflight_reserved，
        // 否则两个 worker 可能同时看到空余名额（A10 并发上限）。
        long reserveVersion = version;
        long reserveEpoch = epoch;
        String reservation = transactionTemplate.execute(status -> {
            if (!inflightAvailable(job)) return "NO_CAPACITY";
            boolean marked = jobRepository.markSubmittingFenced(job.id(), reserveVersion, reserveEpoch,
                    submissionKey, Instant.now().plusSeconds(leaseSeconds));
            return marked ? "RESERVED" : "FENCED";
        });
        if ("NO_CAPACITY".equals(reservation)) {
            // 配额不足：保持 QUEUED，退避后重试（不创建半成品）
            pushNext(job, version, epoch, Instant.now().plusSeconds(5), false, null);
            return;
        }
        if (!"RESERVED".equals(reservation)) {
            return;
        }
        version++;
        var resolved = resolvedFromSnapshot(job);
        var adapter = adapterFor(job.modelSnapshot().adapterKey());
        long attemptNo = nextAttempt(job.id());
        startAttempt(job.id(), attemptNo, AttemptAction.SUBMIT);
        MediaProviderAdapter.SubmitResult result;
        try {
            result = adapter.submit(resolved, job.inputSnapshot(), submissionKey);
        } catch (Exception exception) {
            result = MediaProviderAdapter.SubmitResult.unknown(exception.getMessage());
        }
        finishAttempt(job.id(), attemptNo, outcomeOf(result.outcome()));
        switch (result.outcome()) {
            case ACCEPTED -> {
                meterRegistry.counter("creative.jobs.submitted").increment();
                if (!jobRepository.attachProviderTask(job.id(), version, epoch,
                        modelSnapshot(job).providerId(), result.providerTaskId(), submissionKey)) return;
                transition(job.id(), version + 1, epoch, MediaJobStatus.RUNNING,
                        Instant.now().plusMillis(queryBackoffMs));
            }
            case COMPLETED -> completeWithOutputs(job, version, epoch, result.outputs(), resolved);
            case REJECTED -> {
                if (jobRepository.finishFenced(job.id(), version, epoch,
                        MediaJobStatus.FAILED, result.errorCode(), result.message())) {
                    meterRegistry.counter("creative.jobs.failed").increment();
                    event(job.id(), job.tenantId(), job.userId(), "FAILED");
                }
            }
            case UNKNOWN -> {
                if (result.retryAfterSeconds() > 0) {
                    // 上游明确未受理的限流：回 QUEUED 按 Retry-After 重试提交（不占在途）
                    long fencedVersion = version;
                    long fencedEpoch = epoch;
                    long retryAfter = Math.max(1, result.retryAfterSeconds());
                    transactionTemplate.executeWithoutResult(status -> {
                        var fresh = jobRepository.lockById(job.id()).orElse(null);
                        if (fresh == null || fresh.status() != MediaJobStatus.SUBMITTING) return;
                        if (jobRepository.updateStatusFenced(job.id(), fencedVersion, fencedEpoch,
                                MediaJobStatus.QUEUED, Instant.now().plusSeconds(retryAfter), null)) {
                            jobRepository.releaseInflightFenced(job.id(), fencedVersion + 1, fencedEpoch);
                            event(job.id(), job.tenantId(), job.userId(), "THROTTLED");
                        }
                    });
                    return;
                }
                meterRegistry.counter("creative.jobs.uncertain").increment();
                transition(job.id(), version, epoch, MediaJobStatus.RECONCILING,
                        Instant.now().plusMillis(reconcileDelayMs));
            }
        }
    }

    // ---------------------------------------------------------------- 查询与入库

    private void handleQueryOrIngest(MediaJobRecord job) {
        if (job.providerTaskId() == null) {
            // 无对账依据：保持现状并提示，绝不重新提交（BR11/总体设计 §8.2）
            pushNext(job, job.version(), job.leaseEpoch(),
                    Instant.now().plusMillis(reconcileDelayMs), true, "SUBMISSION_UNCERTAIN");
            return;
        }
        var resolved = resolvedFromSnapshot(job);
        var adapter = adapterFor(job.modelSnapshot().adapterKey());
        long attemptNo = nextAttempt(job.id());
        startAttempt(job.id(), attemptNo, AttemptAction.QUERY);
        MediaProviderAdapter.QueryResult result;
        try {
            result = adapter.query(resolved, job.providerTaskId());
        } catch (Exception exception) {
            result = MediaProviderAdapter.QueryResult.unknown(exception.getMessage());
        }
        finishAttempt(job.id(), attemptNo, outcomeOfQuery(result.state()));
        long version = job.version();
        long epoch = job.leaseEpoch();
        switch (result.state()) {
            case SUCCEEDED -> completeWithOutputs(job, version, epoch, result.outputs(), resolved);
            case RUNNING -> pushNext(job, version, epoch,
                    Instant.now().plusMillis(backoff(attemptNo)), false, null);
            case FAILED -> {
                if (jobRepository.finishFenced(job.id(), version, epoch,
                        MediaJobStatus.FAILED, result.errorCode(), result.message())) {
                    meterRegistry.counter("creative.jobs.failed").increment();
                    event(job.id(), job.tenantId(), job.userId(), "FAILED");
                }
            }
            case UNKNOWN -> {
                boolean attention = attemptNo >= quotaQueriesBeforeAttention;
                pushNext(job, version, epoch,
                        Instant.now().plusMillis(attention ? queryBackoffMaxMs : backoff(attemptNo)),
                        attention, null);
            }
        }
    }

    /** SUCCEEDED 之前的入库：先建/取产物槽位（预分配 assetId），逐槽入库，再收敛状态。 */
    private void completeWithOutputs(MediaJobRecord job, long version, long epoch,
                                     List<MediaProviderAdapter.Output> outputs,
                                     ResolvedMediaModel resolved) {
        if (outputs.isEmpty()) {
            // 成功但无产物：按确认失败收敛，不无限等待（真实供应商不应出现）
            if (jobRepository.finishFenced(job.id(), version, epoch,
                    MediaJobStatus.FAILED, "EMPTY_RESULT", "供应商未返回产物")) {
                event(job.id(), job.tenantId(), job.userId(), "FAILED");
            }
            return;
        }
        long v = version;
        if (job.status() == MediaJobStatus.INGEST_FAILED) {
            // 入库失败重试必须先回到 FETCHING（状态机只允许 INGEST_FAILED → FETCHING）
            if (!jobRepository.updateStatusFenced(job.id(), v, epoch,
                    MediaJobStatus.FETCHING, Instant.now(), null)) return;
            v++;
            event(job.id(), job.tenantId(), job.userId(), "FETCHING");
        }
        var slots = outputRepository.findByJob(job.id());
        if (slots.isEmpty()) {
            long versionForFetch = v;
            transactionTemplate.execute(status -> {
                if (!jobRepository.updateStatusFenced(job.id(), versionForFetch, epoch,
                        MediaJobStatus.FETCHING, Instant.now(), null)) return null;
                int index = 0;
                for (var output : outputs) {
                    UUID assetId = UUID.randomUUID();
                    outputRepository.insert(new MediaJobOutputRecord(UUID.randomUUID(), job.id(),
                            index, assetId, com.zwx.zwxagent.creative.domain.IngestState.PENDING,
                            com.zwx.zwxagent.creative.domain.StorageBackend.LOCAL, objectKeyFor(job, assetId), null, null, null,
                            Instant.now(), Instant.now()));
                    index++;
                }
                event(job.id(), job.tenantId(), job.userId(), "FETCHING");
                return null;
            });
            v++;
            slots = outputRepository.findByJob(job.id());
        }
        boolean allDone = true;
        var downloader = new com.zwx.zwxagent.creative.provider.SafeMediaDownloader(
                downloadAllowLocal, downloadMaxBytes);
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            var output = i < outputs.size() ? outputs.get(i) : null;
            if (output == null) {
                outputRepository.markFailed(slot.id(), "供应商产物数量与槽位不一致");
                allDone = false;
                continue;
            }
            try {
                // 上游返回 URL 时逐跳校验并流式下载（C07）；Mock 直接给字节
                byte[] bytes = payloadOf(output, downloader);
                ingestService.ingest(job, slot, output.mimeType(), bytes);
            } catch (Exception exception) {
                outputRepository.markFailed(slot.id(), exception.getMessage());
                allDone = false;
            }
        }
        if (allDone) {
            if (jobRepository.updateStatusFenced(job.id(), v, epoch,
                    MediaJobStatus.SUCCEEDED, null, Instant.now())) {
                meterRegistry.counter("creative.jobs.succeeded").increment();
                jobRepository.releaseInflightFenced(job.id(), v + 1, epoch);
                event(job.id(), job.tenantId(), job.userId(), "SUCCEEDED");
            }
        } else if (jobRepository.updateStatusFenced(job.id(), v, epoch,
                MediaJobStatus.INGEST_FAILED, Instant.now().plusSeconds(30), null)) {
            meterRegistry.counter("creative.jobs.ingest_failed").increment();
            event(job.id(), job.tenantId(), job.userId(), "INGEST_FAILED");
        }
    }

    // ---------------------------------------------------------------- 取消

    private void handleCancelRequested(MediaJobRecord job) {
        var resolved = resolvedFromSnapshot(job);
        var adapter = adapterFor(job.modelSnapshot().adapterKey());
        long attemptNo = nextAttempt(job.id());
        startAttempt(job.id(), attemptNo, AttemptAction.CANCEL);
        var outcome = adapter.cancel(resolved, job.providerTaskId());
        finishAttempt(job.id(), attemptNo, toOutcome(outcome.name()));
        long version = job.version();
        long epoch = job.leaseEpoch();
        if (outcome == MediaProviderAdapter.CancelOutcome.CONFIRMED) {
            if (jobRepository.finishFenced(job.id(), version, epoch, MediaJobStatus.CANCELLED, null, null)) {
                event(job.id(), job.tenantId(), job.userId(), "CANCELLED");
            }
            return;
        }
        // REQUESTED/UNSUPPORTED：不假称已取消，继续跟踪结果（BR13）
        transition(job.id(), version, epoch, MediaJobStatus.RUNNING,
                Instant.now().plusMillis(queryBackoffMs));
    }

    // ---------------------------------------------------------------- 恢复

    /** 启动恢复（A12/A13）：有 taskId 的任务恢复查询；提交结果不明的进入 RECONCILING。 */
    public void recover() {
        var uncertain = jdbcTemplate.queryForList(
                "SELECT id FROM media_job WHERE status = 'SUBMITTING' AND provider_task_id IS NULL " +
                        "AND lease_until < now()", UUID.class);
        for (var id : uncertain) {
            transactionTemplate.executeWithoutResult(status -> {
                var job = jobRepository.lockById(id).orElse(null);
                if (job == null || job.status() != MediaJobStatus.SUBMITTING) return;
                if (jobRepository.updateStatusFenced(job.id(), job.version(), job.leaseEpoch(),
                        MediaJobStatus.RECONCILING, Instant.now().plusMillis(reconcileDelayMs), null)) {
                    event(job.id(), job.tenantId(), job.userId(), "RECONCILING");
                }
            });
        }
        jdbcTemplate.update("""
                UPDATE media_job SET next_action_at = now(), lease_owner = NULL, lease_until = NULL,
                    version = version + 1, updated_at = now()
                WHERE status IN ('RUNNING','RECONCILING','FETCHING','CANCEL_REQUESTED','INGEST_FAILED')
                  AND provider_task_id IS NOT NULL AND lease_until < now()
                """);
    }

    // ---------------------------------------------------------------- 内部工具

    private ResolvedMediaModel resolvedFromSnapshot(MediaJobRecord job) {
        var snapshot = job.modelSnapshot();
        String cipher = jdbcTemplate.queryForObject(
                "SELECT secret_ciphertext FROM model_provider WHERE id = ?",
                String.class, snapshot.providerId());
        String apiKey = cipher == null || cipher.isBlank() ? null : secretCipher.decrypt(cipher);
        return new ResolvedMediaModel(snapshot.providerId(), snapshot.modelRowId(), snapshot.protocol(),
                snapshot.baseUrl(), snapshot.modelName(), apiKey, snapshot.adapterKey(),
                ParameterSchema.dashscopeTextToImage(), true, 0);
    }

    private boolean inflightAvailable(MediaJobRecord job) {
        // 固定顺序加锁：GLOBAL → PROVIDER → USER
        lockQuota("GLOBAL", "*");
        lockQuota("PROVIDER", String.valueOf(job.modelSnapshot().providerId()));
        lockQuota("USER", job.userId());
        int globalMax = quotaRepository.findMaxInflight("GLOBAL", "*").orElse(8);
        int providerMax = quotaRepository.findMaxInflight("PROVIDER",
                String.valueOf(job.modelSnapshot().providerId())).orElse(4);
        int userMax = quotaRepository.findMaxInflight("USER", job.userId()).orElse(userMaxInflight);
        return jobRepository.countReserved("GLOBAL", null) < globalMax
                && jobRepository.countReserved("PROVIDER",
                job.modelSnapshot().providerId()) < providerMax
                && jobRepository.countReserved("USER", job.userId()) < userMax;
    }

    private void lockQuota(String scopeType, String scopeId) {
        jdbcTemplate.query("SELECT max_inflight FROM media_provider_quota WHERE scope_type = ? AND scope_id = ? FOR UPDATE",
                rs -> { }, scopeType, scopeId);
    }

    private MediaProviderAdapter adapterFor(String adapterKey) {
        var adapter = adapters.get(adapterKey);
        if (adapter == null) throw CreativeException.modelNotConfigured("没有可用的适配器：" + adapterKey);
        return adapter;
    }

    private ModelSnapshot modelSnapshot(MediaJobRecord job) {
        return job.modelSnapshot();
    }

    private long nextAttempt(UUID jobId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_job_attempt WHERE job_id = ?", Integer.class, jobId);
        return (count == null ? 0 : count) + 1;
    }

    private void startAttempt(UUID jobId, long attemptNo, AttemptAction action) {
        jdbcTemplate.update("""
                        INSERT INTO media_job_attempt (id, job_id, attempt_no, action, outcome, started_at)
                        VALUES (?, ?, ?, ?, 'ACCEPTED', now())
                        """,
                ps -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, jobId);
                    ps.setLong(3, attemptNo);
                    ps.setString(4, action.name());
                });
    }

    private void finishAttempt(UUID jobId, long attemptNo, AttemptOutcome outcome) {
        jdbcTemplate.update("""
                UPDATE media_job_attempt SET outcome = ?, finished_at = now()
                WHERE job_id = ? AND attempt_no = ?
                """, outcome.name(), jobId, attemptNo);
    }

    private void transition(UUID jobId, long version, long epoch, MediaJobStatus target, Instant nextActionAt) {
        transactionTemplate.executeWithoutResult(status -> {
            var job = jobRepository.lockById(jobId).orElse(null);
            if (job == null) return;
            if (!jobRepository.updateStatusFenced(jobId, version, epoch, target, nextActionAt, null)) return;
            event(jobId, job.tenantId(), job.userId(), "STATUS_CHANGED");
        });
    }

    private void pushNext(MediaJobRecord job, long version, long epoch, Instant nextActionAt,
                          boolean attentionRequired, String blockedReason) {
        jobRepository.pushNextActionFenced(job.id(), version, epoch, nextActionAt,
                attentionRequired, blockedReason);
    }

    private static byte[] payloadOf(MediaProviderAdapter.Output output,
                                    com.zwx.zwxagent.creative.provider.SafeMediaDownloader downloader) {
        if (output.bytes() != null && output.bytes().length > 0) return output.bytes();
        if (output.url() == null || output.url().isBlank()) {
            throw new IllegalStateException("供应商产物缺少 URL 与字节");
        }
        return downloader.download(output.url());
    }

    private void markAttention(MediaJobRecord job, String reason) {
        jobRepository.pushNextActionFenced(job.id(), job.version(), job.leaseEpoch(),
                Instant.now().plusSeconds(60), true, reason);
    }

    private void event(UUID jobId, String tenantId, String userId, String type) {
        eventRepository.append(jobId, tenantId, userId, type,
                CreativeJson.toJson(Map.of("by", workerId)));
    }

    private static AttemptOutcome outcomeOf(MediaProviderAdapter.SubmitOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED -> AttemptOutcome.ACCEPTED;
            case COMPLETED -> AttemptOutcome.COMPLETED;
            case REJECTED -> AttemptOutcome.REJECTED;
            case UNKNOWN -> AttemptOutcome.UNKNOWN;
        };
    }

    private static AttemptOutcome outcomeOfQuery(MediaProviderAdapter.QueryState state) {
        return switch (state) {
            case SUCCEEDED -> AttemptOutcome.COMPLETED;
            case FAILED -> AttemptOutcome.REJECTED;
            case RUNNING, UNKNOWN -> AttemptOutcome.UNKNOWN;
        };
    }

    private static AttemptOutcome toOutcome(String name) {
        return switch (name) {
            case "CONFIRMED", "REQUESTED" -> AttemptOutcome.ACCEPTED;
            case "UNSUPPORTED" -> AttemptOutcome.REJECTED;
            default -> AttemptOutcome.UNKNOWN;
        };
    }

    private long backoff(long attemptNo) {
        long doubled = queryBackoffMs * (1L << Math.min(5, Math.max(0, attemptNo)));
        return Math.min(doubled, queryBackoffMaxMs);
    }

    private static String objectKeyFor(MediaJobRecord job, UUID assetId) {
        return "creative/" + job.tenantId() + "/" + job.userId() + "/" + job.projectId()
                + "/" + assetId + "/original.png";
    }

}
