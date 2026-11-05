package com.zwx.zwxagent.creative.domain;

/** 存储后端：S3（服务器自建 MinIO）、OSS（阿里云，历史/回滚）、LOCAL（桌面本地目录）。 */
public enum StorageBackend {
    S3,
    OSS,
    LOCAL
}
