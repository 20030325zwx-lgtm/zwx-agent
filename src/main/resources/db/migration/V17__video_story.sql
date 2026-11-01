-- 视频剧情域（P1+P2，2026-11-01）：项目 → 剧集 → 场次 → 镜头，配套角色/地点复用资源、
-- 连续性记录（事实/状态/伏笔）与镜头候选（生成回写 + 人工选片）。
-- 组合外键模式沿用 V13：所有子表 (project_id, tenant_id, user_id) REFERENCES creative_project，
-- 防跨用户关联在 DB 层拒绝；镜头更新使用 version 乐观锁。

CREATE TABLE video_episode (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    episode_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    goal TEXT NOT NULL DEFAULT '',
    synopsis TEXT NOT NULL DEFAULT '',
    script TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_episode_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_episode_number UNIQUE (project_id, episode_number)
);
CREATE INDEX idx_video_episode_project ON video_episode (project_id, episode_number);
COMMENT ON TABLE video_episode IS '剧集：稳定 ID + 顺序号；剧本草稿/确认状态在本表（CONFIRMED 单向）；顺序调整不改变 ID';

CREATE TABLE video_scene (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    episode_id UUID NOT NULL REFERENCES video_episode (id),
    scene_number INT NOT NULL,
    title VARCHAR(200) NOT NULL DEFAULT '',
    location_text VARCHAR(200) NOT NULL DEFAULT '',
    time_of_day VARCHAR(32) NOT NULL DEFAULT '',
    characters_text TEXT NOT NULL DEFAULT '',
    goal TEXT NOT NULL DEFAULT '',
    conflict TEXT NOT NULL DEFAULT '',
    entry_state TEXT NOT NULL DEFAULT '',
    exit_state TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'CONFIRMED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_scene_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_scene_number UNIQUE (episode_id, scene_number)
);
CREATE INDEX idx_video_scene_episode ON video_scene (episode_id, scene_number);
COMMENT ON TABLE video_scene IS '剧情场次：一次具体叙事事件（同一地点可出现在多个场次）；entry_state/exit_state 记录场前/场后差异';

CREATE TABLE video_shot (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    scene_id UUID NOT NULL REFERENCES video_scene (id),
    shot_number INT NOT NULL,
    purpose TEXT NOT NULL DEFAULT '',
    action TEXT NOT NULL DEFAULT '',
    camera TEXT NOT NULL DEFAULT '',
    dialogue TEXT NOT NULL DEFAULT '',
    continuity TEXT NOT NULL DEFAULT '',
    duration_target_seconds INT NOT NULL DEFAULT 4,
    prompt_text TEXT NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'READY', 'GENERATING', 'PENDING_SELECTION', 'SELECTED')),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_shot_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_shot_number UNIQUE (scene_id, shot_number)
);
CREATE INDEX idx_video_shot_scene ON video_shot (scene_id, shot_number);
COMMENT ON TABLE video_shot IS '镜头：审核与重做的最小创作单位；修订（字段编辑）与生成结果（候选）分离；version 乐观锁防并发覆盖';

CREATE TABLE video_character (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    appearance TEXT NOT NULL DEFAULT '',
    personality TEXT NOT NULL DEFAULT '',
    notes TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_character_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_character_name UNIQUE (project_id, name)
);
COMMENT ON TABLE video_character IS '角色：跨集复用资源；固定身份与当场服装/伤势/持有物分开（当场状态记在连续性记录）';

CREATE TABLE video_location (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_location_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_location_name UNIQUE (project_id, name)
);
COMMENT ON TABLE video_location IS '地点：可复用场景资源；「旧车站」是地点，「第 2 集雨夜旧车站对峙」是场次';

CREATE TABLE video_continuity_note (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('FACT', 'STATE', 'FORESHADOW')),
    content TEXT NOT NULL,
    source_label VARCHAR(200) NOT NULL DEFAULT '',
    foreshadow_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (foreshadow_status IN ('PLANTED', 'REINFORCED', 'PENDING', 'PAYOFF')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_note_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id)
);
CREATE INDEX idx_video_note_project ON video_continuity_note (project_id, kind);
COMMENT ON TABLE video_continuity_note IS '剧情连续性记录：已确认事实 / 人物与场景状态 / 伏笔（埋设→强化→待回收→已回收）；AI 只建议，确认由作者完成';

CREATE TABLE video_shot_candidate (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    shot_id UUID NOT NULL REFERENCES video_shot (id),
    asset_id UUID REFERENCES creative_asset (id),
    job_id UUID,
    source VARCHAR(16) NOT NULL DEFAULT 'GENERATED' CHECK (source IN ('GENERATED', 'UPLOAD')),
    prompt_text TEXT NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'GENERATING'
        CHECK (status IN ('GENERATING', 'CANDIDATE', 'SELECTED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_candidate_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_candidate_job UNIQUE (job_id)
);
-- 每个镜头至多一条已选定候选（换选时旧候选退回 CANDIDATE，约束始终成立）
CREATE UNIQUE INDEX uq_video_candidate_selected
    ON video_shot_candidate (shot_id) WHERE status = 'SELECTED';
CREATE INDEX idx_video_candidate_shot ON video_shot_candidate (shot_id, status);
CREATE INDEX idx_video_candidate_job ON video_shot_candidate (job_id) WHERE job_id IS NOT NULL;
COMMENT ON TABLE video_shot_candidate IS '镜头候选：某镜头某次得到的可选素材；生成成功不自动选定（生成→CANDIDATE 待选片，人工选定→SELECTED）；prompt_text 冻结提交文本供回看';

CREATE TABLE video_asset_ref (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    entity_type VARCHAR(16) NOT NULL CHECK (entity_type IN ('CHARACTER', 'LOCATION')),
    entity_id UUID NOT NULL,
    asset_id UUID NOT NULL REFERENCES creative_asset (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_video_ref_project FOREIGN KEY (project_id, tenant_id, user_id)
        REFERENCES creative_project (id, tenant_id, user_id),
    CONSTRAINT uq_video_asset_ref UNIQUE (entity_type, entity_id, asset_id)
);
COMMENT ON TABLE video_asset_ref IS '角色/地点参考图关联；同一参考图可服务多个实体，不复制素材';
