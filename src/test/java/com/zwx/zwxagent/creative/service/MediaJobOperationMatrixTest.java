package com.zwx.zwxagent.creative.service;

import com.zwx.zwxagent.creative.domain.CreativeKind;
import com.zwx.zwxagent.creative.domain.ParameterSchema;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 项目类型×操作矩阵（V1-A，2026-11-01）：IMAGE 项目只做图像操作；
 * VIDEO 项目允许视频操作 + 关键帧图像操作；NOVEL/COMIC 全部拒绝。
 * 纯函数测试（方法为包内静态，同 requestHash 先例）。
 */
class MediaJobOperationMatrixTest {

    @Test
    void imageProjectOnlyAllowsImageOperations() {
        Assertions.assertTrue(MediaJobService.operationAllowedForKind(CreativeKind.IMAGE, "text_to_image"));
        Assertions.assertTrue(MediaJobService.operationAllowedForKind(CreativeKind.IMAGE, "image_edit"));
        Assertions.assertFalse(MediaJobService.operationAllowedForKind(CreativeKind.IMAGE, ParameterSchema.TEXT_TO_VIDEO));
        Assertions.assertFalse(MediaJobService.operationAllowedForKind(CreativeKind.IMAGE, ParameterSchema.IMAGE_TO_VIDEO));
    }

    @Test
    void videoProjectAllowsVideoAndKeyframeImageOperations() {
        Assertions.assertTrue(MediaJobService.operationAllowedForKind(CreativeKind.VIDEO, ParameterSchema.TEXT_TO_VIDEO));
        Assertions.assertTrue(MediaJobService.operationAllowedForKind(CreativeKind.VIDEO, ParameterSchema.IMAGE_TO_VIDEO));
        // 关键帧复用生图/改图链路（规划文档 §6：视频项目允许视频及关键帧图像操作）
        Assertions.assertTrue(MediaJobService.operationAllowedForKind(CreativeKind.VIDEO, "text_to_image"));
        Assertions.assertTrue(MediaJobService.operationAllowedForKind(CreativeKind.VIDEO, "image_edit"));
    }

    @Test
    void novelAndComicProjectsRejectAllOperations() {
        for (String operation : new String[]{"text_to_image", "image_edit",
                ParameterSchema.TEXT_TO_VIDEO, ParameterSchema.IMAGE_TO_VIDEO}) {
            Assertions.assertFalse(MediaJobService.operationAllowedForKind(CreativeKind.NOVEL, operation));
            Assertions.assertFalse(MediaJobService.operationAllowedForKind(CreativeKind.COMIC, operation));
        }
    }
}
