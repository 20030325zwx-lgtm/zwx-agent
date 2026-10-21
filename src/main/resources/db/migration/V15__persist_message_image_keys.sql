-- 持久化用户消息附带的图片对象键，支持刷新/切换会话后继续显示图片。
ALTER TABLE agent_chat_message
    ADD COLUMN IF NOT EXISTS image_object_keys JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN agent_chat_message.image_object_keys IS '用户消息附带的图片对象存储 key 列表，按 agent/conversation 隔离';
