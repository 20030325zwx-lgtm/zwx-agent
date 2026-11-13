package com.zwx.zwxagent.knowledge.cleaning;

/** 清洗动作：落库后可以追溯“这块内容被做了什么”，而不是只看结果。 */
public enum CleaningAction {
    /** 空白/控制字符/全角字母数字等格式规范化。 */
    NORMALIZED,
    /** 与同页上一块合并（修复 PDF 异常换行）。 */
    MERGED_PARAGRAPH,
    /** 整块是页码。 */
    DROPPED_PAGE_NUMBER,
    /** 整块是纯符号/重复字符等无检索意义的噪声。 */
    DROPPED_NOISE,
    /** 跨页高频重复，判定为页眉/页脚。 */
    DROPPED_FURNITURE,
    /** 与本文档中已出现的正文块完全重复。 */
    DROPPED_DUPLICATE,
    /** 含大量乱码，保留但标记人工复核。 */
    GARBLED_REVIEW
}
