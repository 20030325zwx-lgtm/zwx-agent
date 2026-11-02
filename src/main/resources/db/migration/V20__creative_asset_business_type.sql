-- UI3 素材中心：业务分类（可空 = 未归类）+ 项目级检索索引。
-- 只加列与索引，不删除任何历史数据；旧素材保持 NULL，前端显示「未归类」。

ALTER TABLE creative_asset
    ADD COLUMN IF NOT EXISTS business_type VARCHAR(16);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_creative_asset_business_type'
    ) THEN
        ALTER TABLE creative_asset
            ADD CONSTRAINT ck_creative_asset_business_type
            CHECK (business_type IS NULL OR business_type IN ('CHARACTER', 'LOCATION', 'PROP', 'SHOT', 'OTHER'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_creative_asset_business
    ON creative_asset (tenant_id, user_id, business_type, created_at DESC, id DESC);

-- 回填：角色/地点参考图优先（更具体），其余被镜头候选引用的标记为镜头素材
UPDATE creative_asset a
SET business_type = 'CHARACTER'
WHERE a.business_type IS NULL
  AND EXISTS (SELECT 1 FROM video_asset_ref r WHERE r.asset_id = a.id AND r.entity_type = 'CHARACTER');

UPDATE creative_asset a
SET business_type = 'LOCATION'
WHERE a.business_type IS NULL
  AND EXISTS (SELECT 1 FROM video_asset_ref r WHERE r.asset_id = a.id AND r.entity_type = 'LOCATION');

UPDATE creative_asset a
SET business_type = 'SHOT'
WHERE a.business_type IS NULL
  AND EXISTS (SELECT 1 FROM video_shot_candidate c WHERE c.asset_id = a.id);
