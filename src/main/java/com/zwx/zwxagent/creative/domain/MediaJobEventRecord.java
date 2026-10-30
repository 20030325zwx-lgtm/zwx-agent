package com.zwx.zwxagent.creative.domain;

import java.time.Instant;
import java.util.UUID;

/** media_job_event 行记录。seq 由数据库 BIGSERIAL 分配，事件写入必须与状态更新同事务。 */
public record MediaJobEventRecord(long seq, UUID jobId, String tenantId, String userId,
                                  String eventType, String payloadJson, Instant createdAt) {
}
