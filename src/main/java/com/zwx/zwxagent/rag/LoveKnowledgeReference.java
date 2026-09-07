package com.zwx.zwxagent.rag;

public record LoveKnowledgeReference(String filename, Integer section, Integer chunkIndex, String objectKey, String excerpt,
                                     String logicalKey, Integer versionNo) {

    /** 兼容旧调用点：无版本信息（Love 内置知识库暂未接入版本治理）。 */
    public LoveKnowledgeReference(String filename, Integer section, Integer chunkIndex, String objectKey, String excerpt) {
        this(filename, section, chunkIndex, objectKey, excerpt, null, null);
    }
}
