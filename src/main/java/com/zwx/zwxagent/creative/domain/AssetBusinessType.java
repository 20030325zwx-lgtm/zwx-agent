package com.zwx.zwxagent.creative.domain;

import java.util.Locale;

/**
 * 素材业务分类（V20，可空 = 未归类）。
 * 与媒体类型（图片/视频）和执行来源（上传/生成）区分：它回答"这张素材在作品里是什么"。
 */
public enum AssetBusinessType {
    CHARACTER,
    LOCATION,
    PROP,
    SHOT,
    OTHER;

    public static AssetBusinessType parse(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的素材分类：" + value);
        }
    }
}
