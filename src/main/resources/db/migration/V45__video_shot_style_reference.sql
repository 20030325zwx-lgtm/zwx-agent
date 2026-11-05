-- 镜头级画风覆盖与首帧参考（2026-11-06，P1 制作视图）：
-- style_code 空串 = 继承项目默认画风；非空为 ImageStyleCatalog 编码（服务端校验）。
-- reference_asset_ids 只存一个 UUID：首帧图片素材（image_to_video 需要）；归属校验在服务层。
ALTER TABLE video_shot ADD COLUMN style_code VARCHAR(24) NOT NULL DEFAULT '';
ALTER TABLE video_shot ADD COLUMN reference_asset_ids JSONB NOT NULL DEFAULT '[]'::jsonb;
COMMENT ON COLUMN video_shot.style_code IS '镜头画风覆盖：空=继承项目 videoStyle；服务端写入口校验编码';
COMMENT ON COLUMN video_shot.reference_asset_ids IS '首帧参考素材（最多 1 个，IMAGE READY）；生成时决定 text_to_video / image_to_video';
