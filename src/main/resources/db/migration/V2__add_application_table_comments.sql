COMMENT ON TABLE love_conversation IS '恋爱助手会话';
COMMENT ON COLUMN love_conversation.id IS '会话唯一标识';
COMMENT ON COLUMN love_conversation.title IS '会话标题';
COMMENT ON COLUMN love_conversation.created_at IS '创建时间';
COMMENT ON COLUMN love_conversation.updated_at IS '最后更新时间';

COMMENT ON TABLE love_chat_message IS '恋爱助手聊天消息';
COMMENT ON COLUMN love_chat_message.id IS '消息唯一标识';
COMMENT ON COLUMN love_chat_message.conversation_id IS '所属会话标识';
COMMENT ON COLUMN love_chat_message.role IS '消息角色，USER 或 ASSISTANT';
COMMENT ON COLUMN love_chat_message.content IS '消息正文';
COMMENT ON COLUMN love_chat_message.image_object_keys IS '消息关联的对象存储图片键';
COMMENT ON COLUMN love_chat_message.knowledge_references IS '消息引用的知识库片段';
COMMENT ON COLUMN love_chat_message.rag_trace IS '知识库检索过程记录';
COMMENT ON COLUMN love_chat_message.vision_analysis IS '图片视觉分析结果';
COMMENT ON COLUMN love_chat_message.created_at IS '创建时间';

COMMENT ON TABLE love_knowledge_index_job IS '恋爱助手知识库索引任务';
COMMENT ON COLUMN love_knowledge_index_job.id IS '任务唯一标识';
COMMENT ON COLUMN love_knowledge_index_job.status IS '任务状态';
COMMENT ON COLUMN love_knowledge_index_job.document_count IS '处理文档数量';
COMMENT ON COLUMN love_knowledge_index_job.chunk_count IS '生成文本切片数量';
COMMENT ON COLUMN love_knowledge_index_job.error_message IS '失败原因';
COMMENT ON COLUMN love_knowledge_index_job.created_at IS '创建时间';
COMMENT ON COLUMN love_knowledge_index_job.completed_at IS '完成时间';

COMMENT ON TABLE agent_knowledge_document IS '智能体知识库文档';
COMMENT ON COLUMN agent_knowledge_document.id IS '文档记录唯一标识';
COMMENT ON COLUMN agent_knowledge_document.tenant_id IS '租户标识';
COMMENT ON COLUMN agent_knowledge_document.agent_key IS '智能体标识';
COMMENT ON COLUMN agent_knowledge_document.object_key IS '对象存储文件键';
COMMENT ON COLUMN agent_knowledge_document.filename IS '原始文件名';
COMMENT ON COLUMN agent_knowledge_document.status IS '索引状态';
COMMENT ON COLUMN agent_knowledge_document.chunk_count IS '生成文本切片数量';
COMMENT ON COLUMN agent_knowledge_document.error_message IS '失败原因';
COMMENT ON COLUMN agent_knowledge_document.created_at IS '创建时间';
COMMENT ON COLUMN agent_knowledge_document.completed_at IS '完成时间';

COMMENT ON TABLE agent_conversation IS '智能体会话';
COMMENT ON COLUMN agent_conversation.id IS '会话唯一标识';
COMMENT ON COLUMN agent_conversation.tenant_id IS '租户标识';
COMMENT ON COLUMN agent_conversation.agent_key IS '智能体标识';
COMMENT ON COLUMN agent_conversation.title IS '会话标题';
COMMENT ON COLUMN agent_conversation.created_at IS '创建时间';
COMMENT ON COLUMN agent_conversation.updated_at IS '最后更新时间';

COMMENT ON TABLE agent_chat_message IS '智能体聊天消息';
COMMENT ON COLUMN agent_chat_message.id IS '消息唯一标识';
COMMENT ON COLUMN agent_chat_message.conversation_id IS '所属会话标识';
COMMENT ON COLUMN agent_chat_message.role IS '消息角色，USER 或 ASSISTANT';
COMMENT ON COLUMN agent_chat_message.content IS '消息正文';
COMMENT ON COLUMN agent_chat_message.execution_run_id IS '执行运行标识';
COMMENT ON COLUMN agent_chat_message.file_attachments IS '生成文件附件元数据，JSON 数组';
COMMENT ON COLUMN agent_chat_message.created_at IS '创建时间';

COMMENT ON TABLE agent_execution_event IS '智能体执行轨迹事件';
COMMENT ON COLUMN agent_execution_event.id IS '事件唯一标识';
COMMENT ON COLUMN agent_execution_event.run_id IS '执行运行标识';
COMMENT ON COLUMN agent_execution_event.tenant_id IS '租户标识';
COMMENT ON COLUMN agent_execution_event.agent_key IS '智能体标识';
COMMENT ON COLUMN agent_execution_event.conversation_id IS '所属会话标识';
COMMENT ON COLUMN agent_execution_event.sequence IS '同一次执行中的事件序号';
COMMENT ON COLUMN agent_execution_event.phase IS '执行阶段';
COMMENT ON COLUMN agent_execution_event.summary IS '事件摘要';
COMMENT ON COLUMN agent_execution_event.detail IS '事件详细数据，JSON 对象';
COMMENT ON COLUMN agent_execution_event.created_at IS '创建时间';

COMMENT ON TABLE agent_skill_configuration IS '智能体技能配置';
COMMENT ON COLUMN agent_skill_configuration.tenant_id IS '租户标识';
COMMENT ON COLUMN agent_skill_configuration.agent_key IS '智能体标识';
COMMENT ON COLUMN agent_skill_configuration.skill_id IS '技能标识';
COMMENT ON COLUMN agent_skill_configuration.enabled IS '是否启用';
COMMENT ON COLUMN agent_skill_configuration.updated_at IS '最后更新时间';
