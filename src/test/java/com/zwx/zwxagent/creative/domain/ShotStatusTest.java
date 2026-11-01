package com.zwx.zwxagent.creative.domain;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 镜头生产状态机规则（P1/P2）：仅 DRAFT/READY 可发起生成；在途/待选片/已选定必须先处理现有候选。
 */
class ShotStatusTest {

    @Test
    void onlyDraftAndReadyCanGenerate() {
        Assertions.assertTrue(ShotStatus.DRAFT.canGenerate());
        Assertions.assertTrue(ShotStatus.READY.canGenerate());
        Assertions.assertFalse(ShotStatus.GENERATING.canGenerate());
        Assertions.assertFalse(ShotStatus.PENDING_SELECTION.canGenerate());
        Assertions.assertFalse(ShotStatus.SELECTED.canGenerate());
    }
}
