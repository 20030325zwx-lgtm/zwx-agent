package com.zwx.zwxagent.platform.health;

import com.zwx.zwxagent.platform.storage.ObjectStorage;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 对象存储健康指示器：图片、知识文档、工作流产物共用的存储依赖。
 * 探测当前主存储的桶可达性；失败只影响上传/读取类功能，不拉低整体 UP（报 UNKNOWN）。
 */
@Component("objectStorage")
public class ObjectStorageHealthIndicator implements HealthIndicator {

    private final ObjectStorage storage;

    public ObjectStorageHealthIndicator(ObjectStorage storage) {
        this.storage = storage;
    }

    @Override
    public Health health() {
        try {
            boolean reachable = storage.ping();
            Health.Builder builder = reachable ? Health.up() : Health.unknown();
            return builder.withDetail("backend", storage.backend())
                    .withDetail("bucket", storage.bucket())
                    .withDetail("reason", reachable ? "ok" : "bucket 不存在或不可达")
                    .build();
        } catch (IllegalStateException exception) {
            // 凭据未配置：对象存储功能未启用，视为未知而非故障
            return Health.unknown().withDetail("backend", storage.backend())
                    .withDetail("reason", "存储凭据未配置，对象存储功能未启用").build();
        } catch (Exception exception) {
            return Health.unknown().withDetail("backend", storage.backend())
                    .withDetail("error", String.valueOf(exception.getMessage())).build();
        }
    }
}
