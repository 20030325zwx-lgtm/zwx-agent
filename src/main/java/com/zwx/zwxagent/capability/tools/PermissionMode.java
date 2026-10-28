package com.zwx.zwxagent.capability.tools;

/**
 * 工具调用的权限模式，对标 ZCode 的五档语义：
 * <ul>
 *   <li>PLAN：只读分析，写类工具直接拒绝；</li>
 *   <li>BUILD：常规写入（文件产出）需要确认，偏严格；</li>
 *   <li>EDIT：文件产出免确认，执行/修改类工具仍需确认（与引入模式前的行为一致，默认值）；</li>
 *   <li>GUARDED：常规操作免确认，仅高危操作（终端、Git 提交、外部库）逐次确认；</li>
 *   <li>YOLO：全部放行，仅用于隔离环境下的无人值守与评测。</li>
 * </ul>
 */
public enum PermissionMode {

    PLAN,
    BUILD,
    EDIT,
    GUARDED,
    YOLO;

    /** 与引入权限模式前的行为一致：文件产出免确认、执行类需确认。 */
    public static final PermissionMode DEFAULT = EDIT;

    /** 宽松解析：null/空白/未知值一律回落到 {@link #DEFAULT}，避免配置错误放大权限。 */
    public static PermissionMode parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return DEFAULT;
        }
    }

    /** 严格解析：未知值抛异常，用于接收外部请求参数时拒绝非法输入。 */
    public static PermissionMode require(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("无效的权限模式：" + raw);
        }
    }
}
