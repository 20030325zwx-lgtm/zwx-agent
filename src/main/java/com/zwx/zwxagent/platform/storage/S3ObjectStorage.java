package com.zwx.zwxagent.platform.storage;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * S3 兼容存储（服务器自建 MinIO）：path-style 寻址、关闭可选校验和，避免与 MinIO 版本差异冲突。
 * 预签名可选走单独的公网地址（MinIO 默认只在容器网络内可达，浏览器/第三方不应拿内网地址）。
 */
public class S3ObjectStorage implements ObjectStorage {

    private final String endpoint;
    private final String bucket;
    private final String region;
    private final S3Client client;
    private final S3Presigner presigner;

    public S3ObjectStorage(String endpoint, String accessKey, String secretKey, String region,
                           String bucket, String publicEndpoint) {
        if (endpoint == null || endpoint.isBlank()) throw new IllegalArgumentException("S3 存储缺少服务地址");
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("S3 存储缺少访问密钥");
        }
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.region = region == null || region.isBlank() ? "us-east-1" : region;
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .checksumValidationEnabled(false)
                .build();
        this.client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(this.region))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        // 公网地址只用于预签名：签名与 Host 绑定，不能拿内网 endpoint 签给外部
        String presignEndpoint = publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(presignEndpoint))
                .region(Region.of(this.region))
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Configuration)
                .build();
    }

    @Override
    public String backend() {
        return "S3";
    }

    @Override
    public String bucket() {
        return bucket;
    }

    @Override
    public void put(String objectKey, String contentType, InputStream in, long byteSize) {
        if (byteSize < 0) {
            // 未知长度时退回字节缓冲：MinIO/S3 的流式 PUT 需要 Content-Length
            byte[] bytes = readAll(in);
            putBytes(objectKey, contentType, bytes);
            return;
        }
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .contentLength(byteSize)
                    .build(), RequestBody.fromInputStream(in, byteSize));
        } catch (S3Exception exception) {
            throw new IllegalStateException("对象写入失败：" + objectKey, exception);
        }
    }

    @Override
    public void putBytes(String objectKey, String contentType, byte[] bytes) {
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .contentLength((long) bytes.length)
                    .build(), RequestBody.fromBytes(bytes));
        } catch (S3Exception exception) {
            throw new IllegalStateException("对象写入失败：" + objectKey, exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return stream;
        } catch (S3Exception exception) {
            if (isMissing(exception)) throw new ObjectNotFoundException(objectKey);
            throw new IllegalStateException("对象读取失败：" + objectKey, exception);
        }
    }

    @Override
    public long size(String objectKey) {
        try {
            return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()).contentLength();
        } catch (S3Exception exception) {
            if (isMissing(exception)) throw new ObjectNotFoundException(objectKey);
            throw new IllegalStateException("对象 stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public String contentType(String objectKey) {
        try {
            String value = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build()).contentType();
            return value == null || value.isBlank() ? "application/octet-stream" : value;
        } catch (S3Exception exception) {
            if (isMissing(exception)) throw new ObjectNotFoundException(objectKey);
            throw new IllegalStateException("对象 stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
        } catch (S3Exception exception) {
            if (isMissing(exception)) return;
            throw new IllegalStateException("对象删除失败：" + objectKey, exception);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(objectKey).build());
            return true;
        } catch (S3Exception exception) {
            if (isMissing(exception)) return false;
            throw new IllegalStateException("对象 stat 失败：" + objectKey, exception);
        }
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String token = null;
        try {
            do {
                ListObjectsV2Response response = client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix == null ? "" : prefix)
                        .continuationToken(token)
                        .maxKeys(1000)
                        .build());
                response.contents().forEach(item -> keys.add(item.key()));
                token = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (token != null);
        } catch (S3Exception exception) {
            throw new IllegalStateException("对象列举失败：" + prefix, exception);
        }
        return keys;
    }

    @Override
    public Optional<String> presignedGetUrl(String objectKey, Duration ttl, String downloadName) {
        Duration expiry = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofMinutes(15) : ttl;
        var requestBuilder = GetObjectRequest.builder().bucket(bucket).key(objectKey);
        if (downloadName != null && !downloadName.isBlank()) {
            requestBuilder.responseContentDisposition(
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(downloadName, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return Optional.of(presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(expiry)
                        .getObjectRequest(requestBuilder.build())
                        .build())
                .url().toString());
    }

    @Override
    public boolean ping() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (S3Exception exception) {
            return false;
        }
    }

    /** 桶不存在时创建（首次部署/新环境免手工 mc mb）。 */
    public void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception exception) {
            if (exception.statusCode() != 404) {
                throw new IllegalStateException("存储桶不可用：" + bucket + "；" + exception.getMessage(), exception);
            }
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            } catch (BucketAlreadyOwnedByYouException ignored) {
                // 并发创建或已存在，视为成功
            } catch (S3Exception createFailure) {
                throw new IllegalStateException("存储桶创建失败：" + bucket + "；" + createFailure.getMessage(), createFailure);
            }
        }
    }

    public String endpoint() {
        return endpoint;
    }

    private static boolean isMissing(S3Exception exception) {
        return exception.statusCode() == 404 || "NoSuchKey".equals(exception.awsErrorDetails() == null
                ? null : exception.awsErrorDetails().errorCode());
    }

    private static byte[] readAll(InputStream in) {
        try (in) {
            return in.readAllBytes();
        } catch (Exception exception) {
            throw new IllegalStateException("对象读取失败", exception);
        }
    }

    static InputStream stream(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }
}
