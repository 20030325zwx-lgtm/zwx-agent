package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** 生产批次（P3）：业务聚合与停止范围；进度由子项聚合。 */
public record VideoBatchRecord(UUID id, UUID projectId, String tenantId, String userId,
                               String scopeLabel, String status, int concurrency,
                               Instant createdAt, Instant updatedAt) {

    /** 是否还允许提交新子项（STOPPING/STOPPED 停止后续提交，在途不中断）。 */
    public boolean acceptingSubmissions() {
        return "RUNNING".equals(status);
    }
}
