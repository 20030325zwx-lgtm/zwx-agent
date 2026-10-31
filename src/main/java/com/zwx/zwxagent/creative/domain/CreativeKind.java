package com.zwx.zwxagent.creative.domain;

/**
 * 创作项目类型。IMAGE（图片生成）与 NOVEL（章节创作，V13）已有执行链路；
 * VIDEO/COMIC 复用同一项目容器，在对应能力落地前不允许提交生成任务。
 */
public enum CreativeKind {
    IMAGE,
    VIDEO,
    NOVEL,
    COMIC;

    public static CreativeKind parse(String value) {
        if (value == null || value.isBlank()) return IMAGE;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("不支持的创作类型：" + value);
        }
    }

    /** 当前是否已有可执行的生成链路。 */
    public boolean enabled() {
        return this == IMAGE || this == NOVEL;
    }
}
