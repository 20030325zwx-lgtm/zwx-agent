-- M1 项目工作区：补项目级查询索引。
-- 只加索引，不改列、不改 CHECK；workspace 一次查询会按 project_id 扫描
-- 剧集/场次/镜头/候选，任务页按项目过滤也缺索引。

CREATE INDEX IF NOT EXISTS idx_video_scene_project
    ON video_scene (project_id, tenant_id, user_id, episode_id, scene_number);

CREATE INDEX IF NOT EXISTS idx_video_shot_project
    ON video_shot (project_id, tenant_id, user_id, scene_id, shot_number);

CREATE INDEX IF NOT EXISTS idx_video_candidate_project
    ON video_shot_candidate (project_id, tenant_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_video_asset_ref_asset
    ON video_asset_ref (asset_id);

CREATE INDEX IF NOT EXISTS idx_media_job_project
    ON media_job (tenant_id, user_id, project_id, created_at DESC, id DESC);
