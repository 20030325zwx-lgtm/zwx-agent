package com.zwx.aster.eval.job;

import com.zwx.aster.eval.repository.EvalExperimentRepository;
import com.zwx.aster.eval.service.EvalRunner;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 评测实验调度器：单线程轮询认领到期实验（SKIP LOCKED + epoch 租约）。
 * 是本服务的核心后台任务；eval-service 容器默认启用（EVAL_SCHEDULER_ENABLED=true）。
 */
@Component
public class EvalRunScheduler {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EvalRunScheduler.class);

    private final EvalExperimentRepository experimentRepository;
    private final EvalRunner runner;
    private final TransactionTemplate transactionTemplate;

    @Value("${eval.scheduler.enabled:false}")
    private boolean enabled;
    @Value("${eval.scheduler.interval-ms:5000}")
    private long intervalMs;
    @Value("${eval.scheduler.batch-size:1}")
    private int batchSize;
    @Value("${eval.scheduler.lease-seconds:900}")
    private int leaseSeconds;

    private ScheduledExecutorService loop;
    private final String workerId = "eval-worker-" + UUID.randomUUID().toString().substring(0, 8);

    public EvalRunScheduler(EvalExperimentRepository experimentRepository, EvalRunner runner,
                            TransactionTemplate transactionTemplate) {
        this.experimentRepository = experimentRepository;
        this.runner = runner;
        this.transactionTemplate = transactionTemplate;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("评测调度未启用（eval.scheduler.enabled=false）");
            return;
        }
        loop = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "eval-run-scheduler");
            thread.setDaemon(true);
            return thread;
        });
        loop.scheduleWithFixedDelay(this::safeTick, 0, intervalMs, TimeUnit.MILLISECONDS);
        log.info("评测调度已启动：worker={} interval={}ms", workerId, intervalMs);
    }

    @PreDestroy
    void stop() {
        if (loop != null) loop.shutdownNow();
    }

    private void safeTick() {
        try {
            tick();
        } catch (Exception exception) {
            log.warn("评测调度轮次失败：{}", exception.getMessage(), exception);
        }
    }

    /** 认领并执行到期实验（串行阻塞直到完成），返回处理数量。 */
    public int tick() {
        List<EvalExperimentRepository.ClaimToken> tokens = transactionTemplate.execute(status -> {
            var due = experimentRepository.findDueForClaim(Instant.now(), batchSize);
            var claimed = new java.util.ArrayList<EvalExperimentRepository.ClaimToken>(due.size());
            for (var experiment : due) {
                claimed.add(experimentRepository.claim(experiment.id(), workerId,
                        Instant.now().plusSeconds(leaseSeconds)));
            }
            return claimed;
        });
        if (tokens == null || tokens.isEmpty()) return 0;
        int handled = 0;
        for (var token : tokens) {
            try {
                var experiment = experimentRepository.findById(token.experimentId()).orElse(null);
                if (experiment == null) continue;
                runner.run(experiment, token);
                handled++;
            } finally {
                experimentRepository.releaseLease(token.experimentId(), token.leaseEpoch());
            }
        }
        return handled;
    }
}
