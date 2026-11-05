package com.zwx.zwxagent.creative.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * 视频制作设定（P0 制作视图）服务端校验：画风必须是目录编码、画幅受限、目标时长在 10–600 秒。
 */
class CreativeProjectSettingsTest {

    @Test
    void videoStyleNormalizesOrClears() {
        Assertions.assertEquals("FILM_PORTRAIT", CreativeProjectService.validateSetting("videoStyle", " film_portrait "));
        Assertions.assertEquals("", CreativeProjectService.validateSetting("videoStyle", "  "));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CreativeProjectService.validateSetting("videoStyle", "not-a-style"));
    }

    @Test
    void videoAspectIsRestricted() {
        Assertions.assertEquals("9:16", CreativeProjectService.validateSetting("videoAspect", "9:16"));
        Assertions.assertEquals("", CreativeProjectService.validateSetting("videoAspect", ""));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CreativeProjectService.validateSetting("videoAspect", "21:9"));
    }

    @Test
    void videoTargetSecondsHasRange() {
        Assertions.assertEquals("30", CreativeProjectService.validateSetting("videoTargetSeconds", "30"));
        Assertions.assertEquals("", CreativeProjectService.validateSetting("videoTargetSeconds", ""));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CreativeProjectService.validateSetting("videoTargetSeconds", "5"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CreativeProjectService.validateSetting("videoTargetSeconds", "900"));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CreativeProjectService.validateSetting("videoTargetSeconds", "many"));
    }

    @Test
    void legacyTextKeysStillPassThrough() {
        Assertions.assertEquals("讲述三个季节的故事", CreativeProjectService.validateSetting("worldview", "讲述三个季节的故事"));
    }
}
