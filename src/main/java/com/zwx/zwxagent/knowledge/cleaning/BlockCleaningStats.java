package com.zwx.zwxagent.knowledge.cleaning;

/**
 * 块级清洗统计：随文档落库（agent_knowledge_document.cleaning_stats），
 * 用于回答“这次清洗到底丢了多少、为什么丢”，也是回滚判断的依据。
 */
public record BlockCleaningStats(int cleaningVersion,
                                 int blockCount,
                                 int textBlockCount,
                                 int charsBefore,
                                 int charsAfter,
                                 int droppedNoise,
                                 int droppedFurniture,
                                 int droppedDuplicates,
                                 int mergedParagraphs,
                                 int garbledBlocks,
                                 double deletionRatio,
                                 boolean rollbackApplied) {

    static BlockCleaningStats disabled(int blockCount) {
        return new BlockCleaningStats(CleaningSettings.CLEANING_VERSION, blockCount, 0, 0, 0,
                0, 0, 0, 0, 0, 0.0d, false);
    }

    static BlockCleaningStats empty() {
        return disabled(0);
    }
}
