-- 产品收敛：移除情感分析大师、旅游规划专家、功能测试助手，仅保留超级智能体。
-- 共享的 agent_* 表继续服务超级智能体，仅清理已下线智能体的数据。

-- 下线智能体的历史会话（级联消息、run、执行事件与工具调用）。
DELETE FROM agent_conversation WHERE agent_key IN ('love', 'travel', 'test');
DELETE FROM agent_conversation_lock
WHERE conversation_id LIKE '%:love:%' OR conversation_id LIKE '%:travel:%' OR conversation_id LIKE '%:test:%';

-- 下线智能体的 Skill 配置与长期记忆。
DELETE FROM agent_skill_configuration WHERE agent_key IN ('love', 'travel', 'test');
DELETE FROM agent_memory_fact WHERE agent_key IN ('love', 'travel', 'test');

-- 下线智能体的私有知识库数据。
-- agent_knowledge_vector 由 PgVectorStore 在应用启动时（initializeSchema）创建，
-- Flyway 迁移阶段可能尚不存在，因此用 DO 块 + IF 守卫（与 V6 的处理方式一致）。
DO $$
BEGIN
    IF to_regclass('public.agent_knowledge_vector') IS NOT NULL THEN
        DELETE FROM agent_knowledge_vector
        WHERE metadata ->> 'agentKey' IN ('love', 'travel', 'test');
    END IF;
END $$;
DELETE FROM agent_knowledge_document WHERE agent_key IN ('love', 'travel', 'test');
DELETE FROM agent_knowledge_category WHERE agent_key IN ('love', 'travel', 'test');

-- 情感分析大师独占的旧版会话与向量表。
DROP TABLE IF EXISTS love_chat_message;
DROP TABLE IF EXISTS love_conversation;
DROP TABLE IF EXISTS love_knowledge_index_job;
DROP TABLE IF EXISTS love_knowledge_vector;

COMMENT ON TABLE agent_conversation IS '智能体会话；当前仅超级智能体使用';
