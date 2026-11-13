package com.zwx.zwxagent.knowledge.cleaning;

import com.zwx.zwxagent.knowledge.document.MineruBlockParser;

import java.util.List;
import java.util.Map;

/**
 * 块级清洗结果：blocks 是送入切片器的清洗后块；records 按 blockId 保留原文/清洗文本/动作，
 * 由落库侧写入 agent_knowledge_block。清洗被回滚时 blocks 为原始块、records 为空。
 */
public record BlockCleaningResult(List<MineruBlockParser.MineruBlock> blocks,
                                  Map<String, CleanedBlock> records,
                                  BlockCleaningStats stats) {

    public BlockCleaningResult {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        records = records == null ? Map.of() : Map.copyOf(records);
        stats = stats == null ? BlockCleaningStats.empty() : stats;
    }

    /** 清洗未启用/不可用时的原样透传（保证链路行为与清洗前一致）。 */
    public static BlockCleaningResult passthrough(List<MineruBlockParser.MineruBlock> blocks) {
        List<MineruBlockParser.MineruBlock> values = blocks == null ? List.of() : blocks;
        return new BlockCleaningResult(values, Map.of(), BlockCleaningStats.disabled(values.size()));
    }
}
