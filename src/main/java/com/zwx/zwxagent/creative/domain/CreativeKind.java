package com.zwx.zwxagent.creative.domain;

/**
 * 创作项目类型。P1 只实现 IMAGE；VIDEO/NOVEL/COMIC 复用同一项目、草稿与素材容器，
 * 在对应执行链路（C19+）落地前不允许提交生成任务。
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
        return this == IMAGE;
    }
}
