package com.zwx.zwxagent.creative.skill;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 技能运行（skill 设计文档 §8.3 skill_run 的 MVP 子集）：
 * 绑定项目与不可变技能版本，持有输入快照与方案；MediaJob 仍是生成状态真相，
 * run 只聚合方案与任务关联（MVP 一个 run 至多一个 media_job，完整 run_step 留待 P1）。
 * 快照不持久化密钥或临时签名 URL。
 */
public record CreativeSkillRunRecord(
        UUID id,
        UUID projectId,
        String tenantId,
        String userId,
        UUID skillId,
        UUID skillVersionId,
        Map<String, Object> inputSnapshot,
        Map<String, Object> plan,
        String planStatus,
        UUID mediaJobId,
        String failReason,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static final String STATUS_PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_FAILED = "FAILED";
}
