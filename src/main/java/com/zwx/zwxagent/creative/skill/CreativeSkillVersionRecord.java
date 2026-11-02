package com.zwx.zwxagent.creative.skill;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 不可变技能版本（skill 设计文档 §8.3 skill_version 的 MVP 子集）。
 * 正文与来源固定；运行必须绑定具体版本，历史运行的输入不可被新版本改写。
 */
public record CreativeSkillVersionRecord(
        UUID id,
        UUID skillId,
        int version,
        String body,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String sourceType,
        String sourceRef,
        String contentHash,
        String licenseLabel,
        String licenseStatus,
        Instant createdAt) {
}
