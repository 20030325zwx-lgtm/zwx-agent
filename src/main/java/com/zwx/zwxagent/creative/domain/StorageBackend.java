package com.zwx.zwxagent.creative.domain;

/** 存储后端。P1＝云 OSS 与桌面本地目录；MinIO 需另写 S3 适配（总体设计 §11）。 */
public enum StorageBackend {
    OSS,
    LOCAL
}
