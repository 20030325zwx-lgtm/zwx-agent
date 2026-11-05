-- 结构化分镜草稿（2026-11-05，P0 制作视图）：
-- 「生成 → 预览编辑 → 确认写入」中间态。确认前与正式镜头隔离；
-- 采纳用条件状态转移保证同一草稿只写一次；解析失败保留 raw 文本供人工编辑。
CREATE TABLE video_storyboard_draft (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    episode_id UUID,
    scene_id UUID,
    source_episode_version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(24) NOT NULL DEFAULT 'READY'
        CHECK (status IN ('READY', 'CONSUMED', 'DISCARDED', 'PARSE_FAILED')),
    rows JSONB NOT NULL DEFAULT '[]'::jsonb,
    raw_text TEXT NOT NULL DEFAULT '',
    model_name VARCHAR(120) NOT NULL DEFAULT '',
    error TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_storyboard_draft_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id)
);
CREATE INDEX idx_video_storyboard_draft_project
    ON video_storyboard_draft (project_id, tenant_id, user_id, created_at DESC);
COMMENT ON TABLE video_storyboard_draft IS '结构化拆镜草稿：一次生成的镜头行集合；adopt 只允许 READY→CONSUMED 一次转移，PARSE_FAILED 保留原始输出';
