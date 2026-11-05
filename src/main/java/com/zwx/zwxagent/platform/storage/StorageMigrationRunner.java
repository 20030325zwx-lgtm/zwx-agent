package com.zwx.zwxagent.platform.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次性对象迁移：把 OSS 桶（源）全量镜像到 S3/MinIO 桶（目标），对象键保持不变。
 * 由 app.storage.migrate=enabled 触发，默认关闭；已存在且大小一致的对象跳过，可反复执行。
 *
 * <p>迁移在后台线程执行，不阻塞启动（发布/健康检查照常）；结束时输出「总数/复制/跳过/失败」，
 * 失败明细逐条落日志，便于重跑补齐。</p>
 */
@Component
public class StorageMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StorageMigrationRunner.class);

    private final OssObjectStorage source;
    private final ObjectProvider<S3ObjectStorage> targetProvider;
    private final boolean enabled;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public StorageMigrationRunner(OssObjectStorage source, ObjectProvider<S3ObjectStorage> targetProvider,
                                  @Value("${app.storage.migrate:false}") String migrate) {
        this.source = source;
        this.targetProvider = targetProvider;
        this.enabled = migrate != null && ("enabled".equalsIgnoreCase(migrate.trim()) || "true".equalsIgnoreCase(migrate.trim()));
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        S3ObjectStorage target = targetProvider.getIfAvailable();
        if (target == null) {
            log.error("对象迁移：未配置 S3/MinIO（MINIO_ENDPOINT/密钥），跳过");
            return;
        }
        if (!started.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> migrate(target), "storage-migration");
        worker.setDaemon(true);
        worker.start();
    }

    private void migrate(S3ObjectStorage target) {
        long startedAt = System.currentTimeMillis();
        log.info("对象迁移开始：{} → {}（bucket {} → {}）", source.backend(), target.backend(),
                source.bucket(), target.bucket());
        int total = 0;
        int copied = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        try {
            List<String> keys = source.listKeys("");
            total = keys.size();
            for (String key : keys) {
                try {
                    if (target.exists(key)) {
                        skipped++;
                        continue;
                    }
                    String contentType = safeContentType(key);
                    try (InputStream in = source.open(key)) {
                        target.put(key, contentType, in, source.size(key));
                    }
                    copied++;
                    if (copied % 50 == 0) log.info("对象迁移进度：已复制 {} / 共 {}", copied, total);
                } catch (Exception exception) {
                    failures.add(key + " :: " + exception.getMessage());
                    log.warn("对象迁移失败：{}，原因={}", key, exception.getMessage());
                }
            }
        } catch (Exception exception) {
            failures.add("列举对象失败 :: " + exception.getMessage());
            log.error("对象迁移中断：{}", exception.getMessage());
        }
        long seconds = Math.max(0, (System.currentTimeMillis() - startedAt) / 1000);
        log.info("对象迁移结束：总数={} 复制={} 跳过={} 失败={} 耗时={}s", total, copied, skipped, failures.size(), seconds);
        failures.forEach(item -> log.warn("迁移未完成项：{}", item));
    }

    private String safeContentType(String key) {
        try {
            return source.contentType(key);
        } catch (RuntimeException exception) {
            return "application/octet-stream";
        }
    }
}
