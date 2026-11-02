package com.zwx.zwxagent.creative.skill;

import java.util.List;
import java.util.UUID;

/**
 * 创作技能定义（skill 设计文档 §8.3 skill_definition 的 MVP 子集）：
 * 身份/分类/阶段/输入输出/所需能力/生命周期。能力注册区分「所需」与「当前可用」，
 * 可用性由 CreativeSkillService 按用户绑定实时计算，不落库为事实。
 */
public record CreativeSkillRecord(
        UUID id,
        String slug,
        String name,
        String summary,
        String category,
        String stage,
        String inputTypes,
        String outputTypes,
        List<String> requiredCapabilities,
        String visibility,
        String ownerUserId,
        String lifecycle,
        int latestVersion) {

    /** 媒体操作推导：需要首帧图 → image_to_video；仅文本 → text_to_video；纯方案技能（无媒体输出）为 null。 */
    public String mediaOperation() {
        if (requiredCapabilities.contains("IMAGE_TO_VIDEO")) return "image_to_video";
        if (requiredCapabilities.contains("TEXT_TO_VIDEO")) return "text_to_video";
        return null;
    }
}
