package com.zwx.zwxagent.knowledge.cleaning;

/** 切片级质量门禁统计：反映“切片进→切片留”的变化与丢弃原因。 */
public record ChunkGateStats(int chunksIn,
                             int chunksOut,
                             int droppedTooShort,
                             int droppedNoise,
                             int droppedDuplicates,
                             int charsBefore,
                             int charsAfter) {

    static ChunkGateStats empty(int chunksIn) {
        return new ChunkGateStats(chunksIn, chunksIn, 0, 0, 0, 0, 0);
    }
}
