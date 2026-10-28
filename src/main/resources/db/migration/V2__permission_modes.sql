-- 权限模式分级：对标 ZCode 的 plan/build/edit/guarded/yolo 五档 + 用户级 allow/deny 规则
-- 设计文档：docs/design/2026-10-28-permission-modes-and-eval-trajectory.md

-- 用户级默认权限模式与偏好；缺省 EDIT 与引入模式前的行为一致（文件产出免确认、执行类需确认）
CREATE TABLE agent_user_setting (
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    permission_mode VARCHAR(16) NOT NULL DEFAULT 'EDIT',
    json_settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT chk_agent_user_setting_permission_mode
        CHECK (permission_mode IN ('PLAN', 'BUILD', 'EDIT', 'GUARDED', 'YOLO'))
);

COMMENT ON TABLE agent_user_setting IS '用户级设置：默认权限模式与扩展偏好';
COMMENT ON COLUMN agent_user_setting.permission_mode IS '默认权限模式：PLAN/BUILD/EDIT/GUARDED/YOLO';

-- 用户级工具权限规则：pattern 支持精确工具名与尾部通配（如 mcp:*）；DENY 优先于 ALLOW 与模式默认
CREATE TABLE agent_permission_rule (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    tool_pattern VARCHAR(128) NOT NULL,
    effect VARCHAR(8) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_agent_permission_rule_effect CHECK (effect IN ('ALLOW', 'DENY')),
    CONSTRAINT uk_agent_permission_rule UNIQUE (tenant_id, user_id, tool_pattern, effect)
);

CREATE INDEX idx_agent_permission_rule_owner
    ON agent_permission_rule (tenant_id, user_id, created_at);

COMMENT ON TABLE agent_permission_rule IS '用户级工具权限规则：DENY 优先于 ALLOW 与模式默认';
COMMENT ON COLUMN agent_permission_rule.tool_pattern IS '工具名或尾部通配模式，如 executeTerminalCommand、mcp:*';

-- run 级权限模式快照：发起时解析（请求覆盖 > 用户默认），恢复执行沿用快照
ALTER TABLE agent_run
    ADD COLUMN permission_mode VARCHAR(16) NOT NULL DEFAULT 'EDIT';

ALTER TABLE agent_run
    ADD CONSTRAINT chk_agent_run_permission_mode
        CHECK (permission_mode IN ('PLAN', 'BUILD', 'EDIT', 'GUARDED', 'YOLO'));

COMMENT ON COLUMN agent_run.permission_mode IS '本次 run 的权限模式快照；恢复执行沿用，不随用户设置变化';
