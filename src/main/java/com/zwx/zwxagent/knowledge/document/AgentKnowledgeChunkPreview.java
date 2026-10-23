package com.zwx.zwxagent.knowledge.document;

import java.util.List;

/**
 * 引用预览：content 保持旧字段兼容，region/table 用于结构化定位。
 */
public record AgentKnowledgeChunkPreview(String chunkId,
                                         String documentId,
                                         String filename,
                                         Integer chunkIndex,
                                         String content,
                                         String displayText,
                                         Integer pageNumber,
                                         String sectionPath,
                                         String sourceType,
                                         String blockId,
                                         String regionLabel,
                                         boolean legacy,
                                         TableRegion table) {

    public record TableRegion(String caption,
                              List<String> headers,
                              List<List<String>> rows,
                              List<Integer> highlightRows) {
    }
}
