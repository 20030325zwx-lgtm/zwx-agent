-- 查询效率 P1（2026-11-05）：模糊搜索、列表部分索引、缺失外键索引、时间类型统一。
-- 全部为 expand-only：只加扩展/索引/改列类型，不改数据语义，可单独回滚。

-- 1) 模糊搜索：素材名、项目名/简介走 ILIKE '%词%'，无 trigram 索引时是全表扫。
--    pg_trgm 在 PG13+ 是 trusted 扩展，库属主即可安装。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_creative_asset_name_trgm
    ON creative_asset USING gin (display_name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_creative_project_name_trgm
    ON creative_project USING gin (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_creative_project_desc_trgm
    ON creative_project USING gin (description gin_trgm_ops);

-- 2) 素材列表固定过滤 status <> 'DELETED'：部分索引让翻页只走有效行。
CREATE INDEX IF NOT EXISTS idx_creative_asset_owner_active
    ON creative_asset (tenant_id, user_id, created_at DESC, id DESC)
    WHERE status <> 'DELETED';

-- 3) 补缺失的外键索引（PostgreSQL 不会自动为外键建索引；
--    会话消息级联删除、素材关系反查目前都要全表扫）。
CREATE INDEX IF NOT EXISTS idx_agent_run_user_message ON agent_run (user_message_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_assistant_message
    ON agent_run (assistant_message_id) WHERE assistant_message_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_creative_asset_link_target ON creative_asset_link (target_asset_id);

-- 4) 时间类型统一：全库其余表都是 timestamptz，这里两张列漏成了无时区类型。
--    库会话时区为 UTC，历史值即 UTC，按 UTC 归位。
ALTER TABLE agent_tool_confirmation
    ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE agent_tool_confirmation
    ALTER COLUMN decided_at TYPE timestamptz USING decided_at AT TIME ZONE 'UTC';
