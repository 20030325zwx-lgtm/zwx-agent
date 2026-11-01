package com.zwx.zwxagent.creative.domain;

/**
 * 镜头候选状态：GENERATING 生成在途（或上传待确认）→ CANDIDATE 待选片 → SELECTED 已选定；
 * FAILED 生成失败（如实保留，不删除）。每个镜头至多一条 SELECTED（DB 部分唯一索引保证）。
 */
public enum CandidateStatus {
    GENERATING,
    CANDIDATE,
    SELECTED,
    FAILED
}
