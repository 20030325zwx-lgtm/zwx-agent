-- 创作类型：项目不再默认等同图片创作，为视频/小说/漫画预留同一套项目容器。
-- P1 仅开放 IMAGE；其他类型可创建但提交生成会被领域校验拒绝，直到对应能力上线。

ALTER TABLE creative_project ADD COLUMN IF NOT EXISTS kind VARCHAR(16) NOT NULL DEFAULT 'IMAGE';
ALTER TABLE creative_project DROP CONSTRAINT IF EXISTS ck_creative_project_kind;
ALTER TABLE creative_project ADD CONSTRAINT ck_creative_project_kind
    CHECK (kind IN ('IMAGE', 'VIDEO', 'NOVEL', 'COMIC'));

COMMENT ON COLUMN creative_project.kind IS '创作类型：IMAGE 图片（P1 开放）、VIDEO 视频、NOVEL 小说、COMIC 漫画（后续阶段）';
