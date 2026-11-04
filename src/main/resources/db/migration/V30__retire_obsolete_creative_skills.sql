-- 下架与现有能力重叠或偏小众的内置技能（2026-11-04）。
-- 只改 lifecycle，保留技能版本与历史 run 引用；对应种子已从 CreativeSkillContent 移除，
-- 因此重启后不会被 seed() 重新创建。下架可恢复：把 lifecycle 改回 PUBLISHED 并补回种子即可。
UPDATE creative_skill
SET lifecycle = 'RETIRED', updated_at = now()
WHERE slug IN ('camera-performance-designer', 'ambient-motion', 'fashion-look-display')
  AND lifecycle = 'PUBLISHED';
