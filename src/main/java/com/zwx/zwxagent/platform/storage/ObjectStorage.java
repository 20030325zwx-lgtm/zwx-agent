package com.zwx.zwxagent.platform.storage;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 统一对象存储（2026-11-05）：创作素材、知识库文档/图片、工作流产物、对话图片共用一套接口。
 * 主实现为 S3 兼容存储（服务器自建 MinIO）；保留 OSS 实现用于历史对象只读兜底与回滚。
 *
 * <p>objectKey 由服务端生成，不接受用户输入；实现需保证 put 后 open/size 可读、delete 幂等。
 * 对象不存在统一抛 {@link ObjectNotFoundException}，便于路由层回退到备用后端。</p>
 */
public interface ObjectStorage {

    /** 存储后端标识，落库用：S3（MinIO/S3 兼容）、OSS（阿里云）、LOCAL（桌面）。 */
    String backend();

    String bucket();

    void put(String objectKey, String contentType, InputStream in, long byteSize);

    void putBytes(String objectKey, String contentType, byte[] bytes);

    /** 读取对象；不存在抛 {@link ObjectNotFoundException}。调用方负责关闭流。 */
    InputStream open(String objectKey);

    long size(String objectKey);

    /** 对象的 MIME 类型（缺失时返回 application/octet-stream）。 */
    String contentType(String objectKey);

    void delete(String objectKey);

    boolean exists(String objectKey);

    /** 按前缀列举对象键（分页在实现内部处理），用于按文档前缀清理 MinerU 产物等。 */
    List<String> listKeys(String prefix);

    /**
     * 预签名读地址：仅当存储具备外部可达地址时才有意义。
     * downloadName 非空时附带 attachment 下载响应头。
     */
    Optional<String> presignedGetUrl(String objectKey, Duration ttl, String downloadName);

    /** 供外部供应商短时读取素材（等价于不指定文件名的预签名地址）。 */
    default Optional<String> externalReadUrl(String objectKey, Duration ttl) {
        return presignedGetUrl(objectKey, ttl, null);
    }

    /** 健康探针：存储与桶可达即为 true。 */
    boolean ping();
}
