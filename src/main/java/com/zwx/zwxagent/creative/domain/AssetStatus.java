package com.zwx.zwxagent.creative.domain;

/**
 * 素材状态。PENDING＝对象/元数据尚未就绪；READY 之前不能作为生成输入（实施手册 C01）。
 * DELETED 为软删除，历史任务记录不受影响。
 */
public enum AssetStatus {
    PENDING,
    READY,
    DELETED
}
