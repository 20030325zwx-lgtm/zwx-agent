-- 知识库文档版本与有效性治理（design/plans/07-knowledge-document-versioning.md）
-- 逻辑文档 logical_key + 版本链 version_no + 生命周期 lifecycle_status；
-- 部分唯一索引保证同一逻辑文档最多一个 ACTIVE 版本。

ALTER TABLE agent_knowledge_document
    ADD COLUMN IF NOT EXISTS logical_key VARCHAR(255),
    ADD COLUMN IF NOT EXISTS version_no INTEGER,
    ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(16),
    ADD COLUMN IF NOT EXISTS content_sha256 CHAR(64),
    ADD COLUMN IF NOT EXISTS effective_from TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS effective_to TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS supersedes_document_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS conflict_reason TEXT;

-- 回填 logical_key：小写、去掉最后一个扩展名（换格式视为同一逻辑文档）
UPDATE agent_knowledge_document
SET logical_key = lower(regexp_replace(filename, '\.[^.]+$', ''))
WHERE logical_key IS NULL;

-- 回填版本号与生命周期：已有 READY 记录视为当前生效版本（旧行为即时生效）
UPDATE agent_knowledge_document d
SET version_no = ranked.v,
    lifecycle_status = CASE
        WHEN d.status = 'READY' THEN 'ACTIVE'
        WHEN d.status = 'FAILED' THEN 'FAILED'
        ELSE 'INDEXING'
        END,
    effective_from = CASE WHEN d.status = 'READY' THEN d.created_at END,
    published_at = CASE WHEN d.status = 'READY' THEN COALESCE(d.completed_at, d.created_at) END
FROM (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY tenant_id, agent_key, logical_key ORDER BY created_at, id) AS v
    FROM agent_knowledge_document
) ranked
WHERE d.id = ranked.id
  AND d.version_no IS NULL;

ALTER TABLE agent_knowledge_document
    ALTER COLUMN logical_key SET NOT NULL,
    ALTER COLUMN version_no SET NOT NULL,
    ALTER COLUMN lifecycle_status SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_logical_version
    ON agent_knowledge_document (tenant_id, agent_key, logical_key, version_no);

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_knowledge_active_version
    ON agent_knowledge_document (tenant_id, agent_key, logical_key)
    WHERE lifecycle_status = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_retrieval_scope
    ON agent_knowledge_document (tenant_id, agent_key, lifecycle_status);

CREATE INDEX IF NOT EXISTS idx_agent_knowledge_content_hash
    ON agent_knowledge_document (tenant_id, agent_key, content_sha256);

COMMENT ON COLUMN agent_knowledge_document.logical_key IS '逻辑文档标识：同一份业务资料的稳定身份（小写、去扩展名文件名兜底）';
COMMENT ON COLUMN agent_knowledge_document.version_no IS '同一逻辑文档内的版本号，从 1 递增，服务端生成';
COMMENT ON COLUMN agent_knowledge_document.lifecycle_status IS '发布生命周期：UPLOADING/INDEXING/READY/ACTIVE/ARCHIVED/FAILED/CONFLICT，仅 ACTIVE 参与检索';
COMMENT ON COLUMN agent_knowledge_document.content_sha256 IS '原始文件 SHA-256，用于重复内容检测';
COMMENT ON COLUMN agent_knowledge_document.effective_from IS '生效开始时间';
COMMENT ON COLUMN agent_knowledge_document.effective_to IS '生效结束时间（归档时填写）';
COMMENT ON COLUMN agent_knowledge_document.published_at IS '发布时间';
COMMENT ON COLUMN agent_knowledge_document.archived_at IS '归档时间';
COMMENT ON COLUMN agent_knowledge_document.supersedes_document_id IS '被本版本替代的上一版本文档 ID';
COMMENT ON COLUMN agent_knowledge_document.created_by IS '上传者用户名';
COMMENT ON COLUMN agent_knowledge_document.conflict_reason IS '冲突原因说明';

-- 向量切片元数据补齐版本信息（向量表由 PgVectorStore 启动时自动创建，不在 Flyway 管理内，新库跳过）
DO $$
BEGIN
    IF to_regclass('public.agent_knowledge_vector') IS NOT NULL THEN
        UPDATE agent_knowledge_vector v
        SET metadata = (v.metadata::jsonb || jsonb_build_object(
                'lifecycleStatus', d.lifecycle_status,
                'logicalKey', d.logical_key,
                'versionNo', d.version_no))::json
        FROM agent_knowledge_document d
        WHERE v.metadata ->> 'documentId' = d.id
          AND (v.metadata ->> 'lifecycleStatus' IS DISTINCT FROM d.lifecycle_status
           OR v.metadata ->> 'logicalKey' IS DISTINCT FROM d.logical_key
           OR v.metadata ->> 'versionNo' IS DISTINCT FROM d.version_no::text);
    END IF;
END $$;
