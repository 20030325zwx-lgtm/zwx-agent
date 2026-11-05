# 会话摘要：对象存储迁移到自建 MinIO（2026-11-05）

## 本次改动（提交 aa08d31 + 部署 0.7.13）

- 新增 `platform/storage` 统一存储抽象（S3/MinIO、OSS、Local + 路由 + 配置 + 迁移工具），
  删除 `creative/storage`；创作素材、知识库文档与图片、工作流产物、健康检查全部接入。
- 4 条公网外链改造：视频首帧 base64、图片理解字节直传（新增 `VisionAnalysisService`）、
  工作流产物同源签名下载（新增 `ArtifactContentController`/`ArtifactSignatureService`，`JwtAuthFilter` 支持 `?artifact=`）。
- `V41__storage_backend_s3.sql` 放宽 `storage_backend` 约束，允许 `S3`。
- compose 透传 `STORAGE_PROVIDER/STORAGE_FALLBACK_PROVIDER/MINIO_*`。

## 上线与验证（线上已切 MinIO）

- 0.7.13 先以 `STORAGE_PROVIDER=oss` 发布（等价重构），公网 ok。
- 迁移：313/313 对象复制成功、0 失败、56s；MinIO `aster-media` = 313 objects / 80MiB。
- 切 `STORAGE_PROVIDER=s3` + `STORAGE_FALLBACK_PROVIDER=oss`，重启 backend：
  - `/actuator/health` → objectStorage UP / backend=S3 / bucket=aster-media；
  - 历史图片 200（1.46MB）、视频 Range 206、新上传素材落库 `S3` 且回读一致；
  - 产物签名下载 200（image/png 644KB），伪造 token 401。
- 验证用临时项目已归档、测试素材已删除；MinIO 中残留 1 个 70B 测试对象（无害）。

## 本地自测

- 单测：`ObjectStorageRouterTest`(6)、`ArtifactSignatureServiceTest`(4)、`LocalObjectStorageTest`(2)、
  `LkMediaProviderAdapterTest`（新增 base64 分支）、知识库 5 个测试改为 mock `ObjectStorage`、
  `CreativeCanvasServiceTest`(18) 等全部通过。
- 本地以 S3 后端启动验证：上传/回读一致、`storage_backend=S3`、OSS 兜底读取历史素材 200。
- 注意：`MediaJobSchedulerTest` 是 `@SpringBootTest`，本机会挂起，按既有约定未跑全量。

## 遗留与注意事项

- OSS 凭据仍在 `.env`（`ALIYUN_OSS_*`）作为只读兜底；观察 1–2 周无回退后可删除 `STORAGE_FALLBACK_PROVIDER` 与 OSS 凭据。
- 单机 MinIO：备份=备份 `/opt/aster/minio-data`；磁盘 40G 可用，需监控。
- `MINIO_PUBLIC_ENDPOINT` 留空（9443 未对外）；如未来要视频直链再放通公网入口。
- `deploy/docker-compose/.env.example` 被 `.gitignore` 忽略，模板改动未入库。
- 工作区仍有两个访达重名副本：`frontend/src/components/workbench/GlobalNav 2.vue`、
  `docs/context/session-2026-11-05-image-style-catalog 2.md`（非本次生成，未处理，提交时勿误加）。
- 本地后端仍直连生产库（SSH 隧道 15432）；V41 已应用。
