package com.zwx.zwxagent.knowledge.document;

import java.time.Instant;

/**
 * 知识库来源块：与向量切片分开保存，用于回答引用定位。
 */
public record AgentKnowledgeBlock(String id,
                                  String documentId,
                                  String tenantId,
                                  String agentKey,
                                  String blockId,
                                  String sourceType,
                                  Integer pageNumber,
                                  String bbox,
                                  String caption,
                                  String textContent,
                                  String htmlContent,
                                  String jsonContent,
                                  String imageObjectKey,
                                  Instant createdAt) {

    public static final String TEXT = "TEXT";
    public static final String TABLE = "TABLE";
    public static final String FIGURE = "FIGURE";
    public static final String EQUATION = "EQUATION";
}
