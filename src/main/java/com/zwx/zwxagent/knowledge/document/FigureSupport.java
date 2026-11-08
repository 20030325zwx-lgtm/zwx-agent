package com.zwx.zwxagent.knowledge.document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 切片层的图纸上下文：分组结果 + 视觉增强描述。
 * 纯数据载体，切片器保持无 IO 可测；NONE 表示增强不可用（切片层降级为仅图注）。
 */
public record FigureSupport(List<FigureGrouper.FigureGroup> groups,
                            Map<String, FigureGrouper.FigureGroup> groupOfBlockId,
                            Map<String, FigureDescription> descriptions) {

    public static final FigureSupport NONE = new FigureSupport(List.of(), Map.of(), Map.of());

    public static FigureSupport of(List<FigureGrouper.FigureGroup> groups,
                                   Map<String, FigureDescription> descriptions) {
        Map<String, FigureGrouper.FigureGroup> groupOf = new HashMap<>();
        for (FigureGrouper.FigureGroup group : groups) {
            for (MineruBlockParser.MineruBlock block : group.blocks()) {
                if (block.blockId() != null) groupOf.put(block.blockId(), group);
            }
        }
        return new FigureSupport(List.copyOf(groups), Map.copyOf(groupOf), Map.copyOf(descriptions));
    }

    public FigureDescription descriptionOf(MineruBlockParser.MineruBlock block) {
        if (block == null || block.blockId() == null) return null;
        FigureGrouper.FigureGroup group = groupOfBlockId.get(block.blockId());
        return group == null ? null : descriptions.get(group.id());
    }

    public FigureGrouper.FigureGroup groupOf(MineruBlockParser.MineruBlock block) {
        return block == null || block.blockId() == null ? null : groupOfBlockId.get(block.blockId());
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }
}
