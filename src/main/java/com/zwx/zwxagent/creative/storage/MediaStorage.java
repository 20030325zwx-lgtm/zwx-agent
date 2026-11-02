package com.zwx.zwxagent.creative.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;

/**
 * 媒体对象存储抽象（总体设计 §11）。objectKey 由服务端生成（UUID 路径），不接受用户输入；
 * 实现必须保证 put 后 open/stat 可读，delete 幂等。
 */
public interface MediaStorage {

    /** OSS（云端）或 LOCAL（桌面应用数据目录）。 */
    String backend();

    void put(String objectKey, String contentType, InputStream in, long byteSize);

    InputStream open(String objectKey);

    long size(String objectKey);

    void delete(String objectKey);

    /** 供外部供应商短时读取素材；本地存储没有公网地址，默认不支持。 */
    default Optional<String> externalReadUrl(String objectKey, Duration ttl) {
        return Optional.empty();
    }
}
