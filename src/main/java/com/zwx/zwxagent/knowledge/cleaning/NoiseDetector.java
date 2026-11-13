package com.zwx.zwxagent.knowledge.cleaning;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 噪声判定：只对“高置信度的无检索意义内容”返回 true，模糊内容交给人工复核标记，
 * 不在这里删除（对应“对无法确定的内容进行标记，不直接删除”）。
 */
public final class NoiseDetector {

    private static final List<Pattern> PAGE_NUMBER_PATTERNS = List.of(
            Pattern.compile("^\\d{1,4}$"),
            Pattern.compile("^[-—–·\\s]*\\d{1,4}[-—–·\\s]*$"),
            Pattern.compile("^第\\s*\\d{1,4}\\s*页$"),
            Pattern.compile("^第\\s*\\d{1,4}\\s*页\\s*[,，/]?\\s*共\\s*\\d{1,4}\\s*页$"),
            Pattern.compile("^共\\s*\\d{1,4}\\s*页\\s*[,，/]?\\s*第\\s*\\d{1,4}\\s*页$"),
            Pattern.compile("^(?i)(page|p\\.?)\\s*\\d{1,4}(\\s*/\\s*\\d{1,4})?$")
    );

    private static final Pattern SYMBOLS_ONLY = Pattern.compile("^[\\p{P}\\p{S}\\s]+$");
    private static final Pattern REPEATED_CHAR = Pattern.compile("^(\\S)\\1{3,}$");

    private NoiseDetector() {
    }

    /** 纯页码块（丢弃原因需要与“纯符号噪声”区分开，便于统计与审计）。 */
    public static boolean isPageNumber(String text) {
        if (text == null || text.isBlank()) return false;
        String compact = text.strip();
        if (compact.length() > 16) return false;
        for (Pattern pattern : PAGE_NUMBER_PATTERNS) {
            if (pattern.matcher(compact).matches()) return true;
        }
        return false;
    }

    /** 空白块、纯页码、纯符号、单字符重复（“。。。。。”）等可直接丢弃的块。 */
    public static boolean isDroppableNoise(String text) {
        if (text == null || text.isBlank()) return true;
        String compact = text.strip();
        if (compact.length() > 16) return false;
        if (isPageNumber(compact)) return true;
        if (SYMBOLS_ONLY.matcher(compact).matches()) return true;
        return REPEATED_CHAR.matcher(compact).matches();
    }

    /** 替换符/私用区字符占比过高视为乱码：保留原文（可能仍有业务信息），只打标记。 */
    public static boolean isGarbled(String text) {
        if (text == null || text.isEmpty()) return false;
        int broken = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\uFFFD' || Character.getType(value) == Character.PRIVATE_USE) broken++;
        }
        return broken >= 3 && (double) broken / text.length() > 0.15d;
    }
}
