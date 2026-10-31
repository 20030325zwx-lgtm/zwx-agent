-- R07（2026-10-31 评审）：档案改为"解析只创建、不覆盖"后，为既有 openai-images 档案
-- 一次性回填 image_edit 能力（此前版本自动创建的档案只有 text_to_image）。
UPDATE media_model_profile
SET operations = operations || '{"image_edit": true}'::jsonb,
    revision = revision + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE adapter_key = 'openai-images'
  AND NOT (operations ? 'image_edit');
