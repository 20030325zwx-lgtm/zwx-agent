package com.zwx.zwxagent.rag;

public record LoveRetrievalCandidate(String filename, Integer section, String objectKey, Double score, Double rerankScore) {

    /** 兼容旧调用点：无重排分。 */
    public LoveRetrievalCandidate(String filename, Integer section, String objectKey, Double score) {
        this(filename, section, objectKey, score, null);
    }
}
