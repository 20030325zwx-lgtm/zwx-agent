package com.zwx.zwxagent.knowledge.cleaning;

import java.util.List;

/**
 * 单个来源块的清洗记录：原文与清洗结果成对保留，dropped 表示该块不进入切片。
 *
 * @param blockId       MinerU 块标识（同一文档版本内唯一）
 * @param pageNumber    页码，未知为 null（不编造）
 * @param originalText  解析原文
 * @param cleanedText   清洗后文本；块被丢弃时保留清洗后的内容供审计
 * @param dropped       是否被清洗丢弃（不进入切片，但原文仍落库）
 * @param actions       实际执行的清洗动作
 * @param reviewRequired 是否建议人工复核（乱码、合并存疑等）
 */
public record CleanedBlock(String blockId,
                           Integer pageNumber,
                           String originalText,
                           String cleanedText,
                           boolean dropped,
                           List<CleaningAction> actions,
                           boolean reviewRequired) {

    public CleanedBlock {
        actions = actions == null ? List.of() : List.copyOf(actions);
        originalText = originalText == null ? "" : originalText;
        cleanedText = cleanedText == null ? "" : cleanedText;
    }
}
