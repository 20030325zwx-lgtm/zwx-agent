# 对象存储迁移：阿里云 OSS → 服务器自建 MinIO（2026-11-05）

状态：已实施并上线（0.7.13 + 配置切换）。历史对象已全量镜像到 MinIO，OSS 保留只读兜底。

## 1. 背景与目标

图片、视频、知识库文档、工作流产物、对话图片原先全部落在阿里云 OSS（`app.oss.*` + 阿里云 SDK）。
服务器上已有 systemd 版 MinIO（`/opt/aster/minio-data`、bucket `aster-media`），但代码零引用。

目标：应用真正改用自建 MinIO；MinIO 只在内网（不开公网域名）；迁移期保留 OSS 只读兜底，最终可停用 OSS 凭据。

## 2. 统一存储抽象

新增 `src/main/java/com/zwx/zwxagent/platform/storage/`：

| 类 | 职责 |
| --- | --- |
| `ObjectStorage` | 统一接口：put/putBytes/open/size/delete/exists/listKeys/contentType/presignedGetUrl/ping |
| `S3ObjectStorage` | AWS SDK v2，path-style + 关闭校验和；启动时 `ensureBucket()`；可选 `public-endpoint` 仅用于预签名 |
| `OssObjectStorage` | 阿里云 SDK（历史对象读取/回滚） |
| `LocalObjectStorage` | 桌面本地目录，保留符号链接越界校验 |
| `ObjectStorageRouter` | 写/列举走主存储；读取主存储缺失（`ObjectNotFoundException`）时回退备用 |
| `ObjectStorageConfig` | 按 `app.storage.provider`（s3/oss/local）装配；provider=oss 且无凭据时回退本地并告警；provider=s3 配置不全直接失败 |
| `StorageMigrationRunner` | `app.storage.migrate=enabled` 时后台线程把 OSS 桶全量镜像到 S3 桶（跳已存在，输出统计） |

原 `creative/storage/MediaStorage` 一族删除；创作素材、知识库文档与图片、工作流、健康检查全部改用 `ObjectStorage`。
`storage_backend` 新增取值 `S3`（`V41__storage_backend_s3.sql` 放宽两个 CHECK 约束）。

## 3. 去掉 4 条公网依赖

MinIO 只在容器网络内可达，原先直接交给外部的地址全部改造：

| 原链路 | 改造 |
| --- | --- |
| Lk/图生视频首帧 URL | 无 URL 时发送 `data:<mime>;base64`（平台契约 ≤10MB）；OpenAI 视频仍要求 URL，缺失时明确提示未配置公网入口 |
| 工作流 `image_understand`（OSS 预签名 → image MCP → DashScope） | 直接读字节送多模态视觉模型（`WorkflowService.understandLocalAsset`） |
| Manus 对话图片理解（预签名 URL → MCP） | 新增 `VisionAnalysisService` 字节直传；读取失败按原语义降级 |
| 工作流产物浏览器 `<img>/<a>` 直连预签名 | 新增 `ArtifactContentController` + `ArtifactSignatureService`：同源 `/api/ai/workflows/artifacts/content?key=&artifact=`，支持 Range/206 与下载文件名；`JwtAuthFilter` 按 `?artifact=` 装配归属者身份 |

## 4. 配置

```yaml
app.storage.provider: ${STORAGE_PROVIDER:oss}          # s3 | oss | local
app.storage.fallback-provider: ${STORAGE_FALLBACK_PROVIDER:}
app.storage.local-dir: ${CREATIVE_LOCAL_DIR:...}
app.storage.migrate: ${STORAGE_MIGRATE:false}          # enabled 时启动后镜像
app.storage.s3.{endpoint,access-key,secret-key,region,bucket,public-endpoint}
```

服务器 `.env`（已配置）：`STORAGE_PROVIDER=s3`、`STORAGE_FALLBACK_PROVIDER=oss`、`MINIO_ENDPOINT=http://172.17.0.1:9000`、
`MINIO_ACCESS_KEY/SECRET_KEY`（与 `/opt/aster/minio.env` 同源）、`MINIO_BUCKET=aster-media`、`MINIO_REGION=us-east-1`。
compose 已透传这些键。`MINIO_PUBLIC_ENDPOINT` 留空（9443 未在公网开放）。

## 5. 上线过程（实际执行）

1. 服务器备份 `.env`/`docker-compose.yml` → 上传新 compose → `.env` 去重并补 MinIO 键（先 `provider=oss`）。
2. 发布 0.7.13（等价重构，行为不变）：Flyway 应用 V41，backend healthy，公网 ok。
3. 本地通过 SSH 隧道（19000 → 服务器 9000）运行 `STORAGE_MIGRATE=enabled`：
   **总数 313 / 复制 313 / 跳过 0 / 失败 0 / 耗时 56s**；MinIO 桶核对 313 objects / 80MiB。
4. `.env` 切 `STORAGE_PROVIDER=s3` + `STORAGE_FALLBACK_PROVIDER=oss`，重启 backend。
5. 验收：`/actuator/health` 的 objectStorage = UP/S3/aster-media；迁移后的历史图片 200（1.46MB）；
   视频 Range 206（`bytes 0-1023/2261746`）；新上传素材落库 `storage_backend=S3` 且回读一致；
   产物签名下载 200（image/png 644KB）；伪造 token 401。

## 6. 回滚

```sh
cd /opt/aster
sed -i 's/^STORAGE_PROVIDER=.*/STORAGE_PROVIDER=oss/; s/^STORAGE_FALLBACK_PROVIDER=.*/STORAGE_FALLBACK_PROVIDER=/' .env
docker compose up -d --no-deps backend
```

切回 OSS 期间 MinIO 新增对象不会同步回 OSS；如需回滚后继续用旧对象，可保留 fallback 指向 s3（反向兜底）再补迁移。

## 7. 风险与后续

- **单机 MinIO**：数据在 `/opt/aster/minio-data`，备份=目录备份；磁盘 40G 可用（当前 80MiB 已用），需监控。
- **视频播放仍经服务器中转**（5Mbps 约 0.6MB/s）；若后续要直链，可启用 `MINIO_PUBLIC_ENDPOINT` 并放通公网入口。
- OpenAI 视频供应商只接受 URL：若切到该供应商且未配置公网入口，会明确拒绝（其余供应商走 base64）。
- 观察 1–2 周无回退后，可从 `.env` 删除 `ALIYUN_OSS_*` 与 `STORAGE_FALLBACK_PROVIDER`，彻底脱钩。
- `deploy/docker-compose/.env.example` 被仓库 `.gitignore` 忽略，模板更新无法入库（本地已同步）。
