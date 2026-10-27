ALTER TABLE agent_project
    ADD COLUMN IF NOT EXISTS path_scope VARCHAR(16) NOT NULL DEFAULT 'ROOT';

ALTER TABLE agent_project
    ADD COLUMN IF NOT EXISTS workspace_path VARCHAR(1024);

UPDATE agent_project
SET workspace_path = directory_name
WHERE workspace_path IS NULL;

ALTER TABLE agent_project
    ALTER COLUMN workspace_path SET NOT NULL;

ALTER TABLE agent_project
    DROP CONSTRAINT IF EXISTS uk_agent_project_owner_directory;

ALTER TABLE agent_project
    ADD CONSTRAINT uk_agent_project_owner_path
        UNIQUE (tenant_id, owner_user_id, path_scope, workspace_path);

ALTER TABLE agent_project
    ADD CONSTRAINT ck_agent_project_path_scope
        CHECK (path_scope IN ('ROOT', 'LOCAL'));
