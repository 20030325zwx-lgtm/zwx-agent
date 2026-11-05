-- 对象存储迁移（2026-11-05）：新增 S3（服务器自建 MinIO）后端标识。
-- 历史行保持 OSS/LOCAL 不变：读取时由存储路由按「主存储 → OSS 只读兜底」处理，无需回填。
ALTER TABLE creative_asset DROP CONSTRAINT IF EXISTS creative_asset_storage_backend_check;
ALTER TABLE creative_asset ADD CONSTRAINT creative_asset_storage_backend_check
    CHECK (storage_backend IN ('S3', 'OSS', 'LOCAL'));

ALTER TABLE media_job_output DROP CONSTRAINT IF EXISTS media_job_output_storage_backend_check;
ALTER TABLE media_job_output ADD CONSTRAINT media_job_output_storage_backend_check
    CHECK (storage_backend IN ('S3', 'OSS', 'LOCAL'));
