package com.zwx.zwxagent.knowledge.retrieval;

public record RagTraceCandidate(String documentId, String filename, String logicalKey, Integer chunkIndex,
                                Integer pageNumber, String excerpt, Integer vectorRank, Double vectorScore,
                                Integer lexicalRank, Integer lexicalScore, Integer fusedRank, Double fusionScore,
                                Integer rerankRank, Double rerankScore, boolean selected,
                                String chunkId, String sourceDocumentId, String blockId, String sourceType,
                                Integer rowStart, Integer rowEnd, String regionLabel) {
}
