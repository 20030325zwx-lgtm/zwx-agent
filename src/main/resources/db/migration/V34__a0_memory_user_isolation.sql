-- A0（工作 Agent 整改批次 A0）：长期记忆按用户隔离
-- 旧唯一键 (tenant_id, agent_key, fact_hash) 缺 user_id：
-- 1) factsFor 注入查询不区分用户，同租户跨用户注入事实；
-- 2) upsert 去重冲突目标不含 user，用户 B 的新事实会被用户 A 的同文事实挡掉。
-- 处置：删除全局唯一索引，改为仅对可归属记录（user_id 非空）按用户去重的部分唯一索引；
-- user_id 为空的存量记录保留但永不注入（注入查询要求 user_id 精确匹配）。
-- 旧索引已保证 (tenant, agent, hash) 全局唯一，因此子集上不会出现重复，可直接建新索引。

DROP INDEX IF EXISTS uk_agent_memory_fact;

CREATE UNIQUE INDEX IF NOT EXISTS uk_agent_memory_fact_user
    ON agent_memory_fact (tenant_id, user_id, agent_key, fact_hash)
    WHERE user_id IS NOT NULL;

COMMENT ON TABLE agent_memory_fact IS '长期记忆事实日志：按 (tenant, user, agent, fact_hash) 去重，仅可归属记录参与注入；无主记录保留待治理但不注入';
