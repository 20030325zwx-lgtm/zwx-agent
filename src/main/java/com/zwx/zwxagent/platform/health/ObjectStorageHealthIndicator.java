package com.zwx.zwxagent.platform.health;

import com.zwx.zwxagent.knowledge.storage.OssClientProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 对象存储健康指示器：图片与知识文档的可选依赖。探测 bucket 可达性；
 * 失败只影响上传/读取图片与文档的功能，不拉低整体 UP（报 UNKNOWN）。
 */
@Component("objectStorage")
public class ObjectStorageHealthIndicator implements HealthIndicator {

    private final OssClientProvider ossClientProvider;
    private final String bucket;

    public ObjectStorageHealthIndicator(OssClientProvider ossClientProvider,
                                        @Value("${app.oss.bucket:}") String bucket) {
        this.ossClientProvider = ossClientProvider;
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            boolean exists = ossClientProvider.getClient().doesBucketExist(bucket);
            return exists
                    ? Health.up().withDetail("bucket", bucket).build()
                    : Health.unknown().withDetail("bucket", bucket)
                            .withDetail("reason", "bucket 不存在或不可达").build();
        } catch (IllegalStateException exception) {
            // 凭据未配置：对象存储功能未启用，视为未知而非故障
            return Health.unknown().withDetail("reason", "OSS 凭据未配置，对象存储功能未启用").build();
        } catch (Exception exception) {
            return Health.unknown().withDetail("bucket", bucket)
                    .withDetail("error", String.valueOf(exception.getMessage())).build();
        }
    }
}
