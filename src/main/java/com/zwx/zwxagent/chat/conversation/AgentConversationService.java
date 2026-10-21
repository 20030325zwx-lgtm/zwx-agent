package com.zwx.zwxagent.chat.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AgentConversationService {

    private static final String TRAVEL = "travel";
    private static final String DEFAULT_TITLE = "新的旅行规划";
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentConversationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AgentConversationSummary createTravelConversation(String tenantId, String userId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO agent_conversation (id, tenant_id, user_id, agent_key, title) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, userId, TRAVEL, DEFAULT_TITLE);
        return getTravelConversation(tenantId, userId, id);
    }

    public void ensureTravelConversation(String tenantId, String userId, String conversationId, String firstMessage) {
        requireConversationId(conversationId);
        jdbcTemplate.update("""
                INSERT INTO agent_conversation (id, tenant_id, user_id, agent_key, title) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    title = CASE WHEN agent_conversation.title = ? THEN EXCLUDED.title ELSE agent_conversation.title END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE agent_conversation.tenant_id = EXCLUDED.tenant_id
                  AND agent_conversation.user_id = EXCLUDED.user_id
                  AND agent_conversation.agent_key = EXCLUDED.agent_key
                """, conversationId, tenantId, userId, TRAVEL, toTitle(firstMessage), DEFAULT_TITLE);
        requireOwned(tenantId, userId, TRAVEL, conversationId);
    }

    public void appendTravelMessage(String tenantId, String userId, String conversationId, String role, String content) {
        appendTravelMessage(tenantId, userId, conversationId, role, content, null);
    }

    public void appendTravelMessage(String tenantId, String userId, String conversationId, String role, String content, String executionRunId) {
        requireOwned(tenantId, userId, TRAVEL, conversationId);
        int inserted = jdbcTemplate.update("""
                INSERT INTO agent_chat_message (conversation_id, role, content, execution_run_id)
                SELECT id, ?, ?, ? FROM agent_conversation WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?
                """, role, content == null ? "" : content, executionRunId, conversationId, tenantId, userId, TRAVEL);
        if (inserted == 0) throw new IllegalArgumentException("Travel conversation was not found");
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?",
                conversationId, tenantId, userId, TRAVEL);
    }

    @Transactional
    public void saveCompletedTravelTurn(String tenantId, String userId, String conversationId, String message, String answer, String executionRunId) {
        ensureTravelConversation(tenantId, userId, conversationId, message);
        appendTravelMessage(tenantId, userId, conversationId, "USER", message);
        appendTravelMessage(tenantId, userId, conversationId, "ASSISTANT", answer, executionRunId);
    }

    public List<AgentConversationSummary> listTravelConversations(String tenantId, String userId) {
        return jdbcTemplate.query("""
                SELECT id, title, created_at, updated_at FROM agent_conversation
                WHERE tenant_id = ? AND user_id = ? AND agent_key = ? ORDER BY updated_at DESC
                """, (rs, rowNum) -> new AgentConversationSummary(rs.getString("id"), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), tenantId, userId, TRAVEL);
    }

    public List<AgentConversationMessage> getTravelMessages(String tenantId, String userId, String conversationId) {
        requireOwned(tenantId, userId, TRAVEL, conversationId);
        return jdbcTemplate.query("""
                SELECT m.id, m.role, m.content, m.execution_run_id, m.file_attachments::text AS file_attachments,
                       m.rag_retrieval_trace::text AS rag_retrieval_trace, m.image_object_keys::text AS image_object_keys,
                       m.created_at, m.status FROM agent_chat_message m
                JOIN agent_conversation c ON c.id = m.conversation_id
                WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ? ORDER BY m.id ASC
                """, (rs, rowNum) -> new AgentConversationMessage(rs.getLong("id"), rs.getString("role"), rs.getString("content"), rs.getString("execution_run_id"),
                rs.getString("file_attachments"), rs.getString("rag_retrieval_trace"), rs.getTimestamp("created_at").toInstant(), rs.getString("status"),
                rs.getString("image_object_keys")), conversationId, tenantId, userId, TRAVEL);
    }

    public List<AgentConversationMessage> getRecentTravelMessages(String tenantId, String userId, String conversationId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, role, content, execution_run_id, file_attachments, rag_retrieval_trace, created_at, status FROM (
                    SELECT m.id, m.role, m.content, m.execution_run_id, m.file_attachments::text AS file_attachments,
                           m.rag_retrieval_trace::text AS rag_retrieval_trace, m.image_object_keys::text AS image_object_keys,
                           m.created_at, m.status FROM agent_chat_message m
                    JOIN agent_conversation c ON c.id = m.conversation_id
                    WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ? ORDER BY m.id DESC LIMIT ?
                ) recent_messages ORDER BY id ASC
                """, (rs, rowNum) -> new AgentConversationMessage(rs.getLong("id"), rs.getString("role"), rs.getString("content"), rs.getString("execution_run_id"),
                rs.getString("file_attachments"), rs.getString("rag_retrieval_trace"), rs.getTimestamp("created_at").toInstant(), rs.getString("status"),
                rs.getString("image_object_keys")), conversationId, tenantId, userId, TRAVEL, limit);
    }

    public void deleteTravelConversation(String tenantId, String userId, String conversationId) {
        requireOwned(tenantId, userId, TRAVEL, conversationId);
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?",
                conversationId, tenantId, userId, TRAVEL);
    }

    public String deleteTravelAssistantReply(String tenantId, String userId, String conversationId, long userMessageId) {
        requireOwned(tenantId, userId, TRAVEL, conversationId);
        List<TravelReply> replies = jdbcTemplate.query("""
                SELECT reply.id, reply.execution_run_id FROM agent_chat_message reply
                JOIN agent_conversation c ON c.id = reply.conversation_id
                WHERE reply.conversation_id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ? AND reply.role = 'ASSISTANT' AND reply.id > ?
                  AND NOT EXISTS (SELECT 1 FROM agent_chat_message later_user
                                  WHERE later_user.conversation_id = reply.conversation_id AND later_user.role = 'USER'
                                    AND later_user.id > ? AND later_user.id < reply.id)
                ORDER BY reply.id LIMIT 1
                """, (rs, rowNum) -> new TravelReply(rs.getLong("id"), rs.getString("execution_run_id")),
                conversationId, tenantId, userId, TRAVEL, userMessageId, userMessageId);
        if (replies.isEmpty()) return null;
        TravelReply reply = replies.getFirst();
        jdbcTemplate.update("DELETE FROM agent_chat_message WHERE id = ?", reply.id());
        return reply.executionRunId();
    }

    public AgentConversationSummary createConversation(String tenantId, String userId, String agentKey, String defaultTitle) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO agent_conversation (id, tenant_id, user_id, agent_key, title) VALUES (?, ?, ?, ?, ?)",
                id, tenantId, userId, agentKey, defaultTitle);
        return jdbcTemplate.queryForObject("SELECT id, title, created_at, updated_at FROM agent_conversation WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?",
                (rs, rowNum) -> new AgentConversationSummary(rs.getString("id"), rs.getString("title"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                id, tenantId, userId, agentKey);
    }

    @Transactional
    public void saveCompletedTurn(String tenantId, String userId, String agentKey, String conversationId, String defaultTitle, String message, String answer) {
        ensureConversation(tenantId, userId, agentKey, conversationId, defaultTitle, message);
        appendMessage(tenantId, userId, agentKey, conversationId, "USER", message);
        appendMessage(tenantId, userId, agentKey, conversationId, "ASSISTANT", answer);
    }

    @Transactional
    public void saveCompletedTurn(String tenantId, String userId, String agentKey, String conversationId, String defaultTitle, String message, String answer, String fileAttachments) {
        saveCompletedTurn(tenantId, userId, agentKey, conversationId, defaultTitle, message, answer, fileAttachments, null);
    }

    @Transactional
    public void saveCompletedTurn(String tenantId, String userId, String agentKey, String conversationId, String defaultTitle,
                                  String message, String answer, String fileAttachments, String ragRetrievalTrace) {
        ensureConversation(tenantId, userId, agentKey, conversationId, defaultTitle, message);
        appendMessage(tenantId, userId, agentKey, conversationId, "USER", message);
        int inserted = jdbcTemplate.update("""
                INSERT INTO agent_chat_message (conversation_id, role, content, file_attachments, rag_retrieval_trace)
                SELECT id, 'ASSISTANT', ?, CAST(? AS jsonb), CAST(? AS jsonb) FROM agent_conversation
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?
                """, answer == null ? "" : answer, fileAttachments == null ? "[]" : fileAttachments,
                ragRetrievalTrace == null ? "{}" : ragRetrievalTrace, conversationId, tenantId, userId, agentKey);
        if (inserted == 0) throw new IllegalArgumentException("Agent conversation was not found");
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?",
                conversationId, tenantId, userId, agentKey);
    }

    public void ensureConversation(String tenantId, String userId, String agentKey, String conversationId, String defaultTitle, String firstMessage) {
        requireConversationId(conversationId);
        jdbcTemplate.update("""
                INSERT INTO agent_conversation (id, tenant_id, user_id, agent_key, title) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    title = CASE WHEN agent_conversation.title = ? THEN EXCLUDED.title ELSE agent_conversation.title END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE agent_conversation.tenant_id = EXCLUDED.tenant_id
                  AND agent_conversation.user_id = EXCLUDED.user_id
                  AND agent_conversation.agent_key = EXCLUDED.agent_key
                """, conversationId, tenantId, userId, agentKey, title(firstMessage, defaultTitle), defaultTitle);
        requireOwned(tenantId, userId, agentKey, conversationId);
    }

    public boolean hasConversation(String tenantId, String userId, String agentKey, String conversationId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_conversation WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?",
                Integer.class, conversationId, tenantId, userId, agentKey);
        return count != null && count > 0;
    }

    @Transactional
    public long startUserTurn(String tenantId, String userId, String agentKey, String conversationId, String message, String clientRequestId) {
        return startUserTurn(tenantId, userId, agentKey, conversationId, message, List.of(), clientRequestId);
    }

    @Transactional
    public long startUserTurn(String tenantId, String userId, String agentKey, String conversationId, String message,
                              List<String> imageObjectKeys, String clientRequestId) {
        ensureConversation(tenantId, userId, agentKey, conversationId, "新的对话", message);
        markStaleUserTurnsInterrupted(tenantId, userId, agentKey, conversationId);
        requireNoDuplicateRequest(tenantId, userId, clientRequestId);
        String imageKeysJson = "[]";
        if (imageObjectKeys != null && !imageObjectKeys.isEmpty()) {
            try {
                imageKeysJson = objectMapper.writeValueAsString(imageObjectKeys);
            } catch (Exception exception) {
                throw new IllegalArgumentException("Unable to serialize image keys", exception);
            }
        }
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO agent_chat_message (conversation_id, role, content, status, client_request_id, image_object_keys)
                SELECT id, 'USER', ?, 'IN_PROGRESS', ?, CAST(? AS jsonb) FROM agent_conversation
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?
                RETURNING id
                """, Long.class, message == null ? "" : message, clientRequestId, imageKeysJson, conversationId, tenantId, userId, agentKey);
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
        if (id == null) throw new IllegalArgumentException("Agent conversation was not found");
        return id;
    }

    public void requireNoDuplicateRequest(String tenantId, String userId, String clientRequestId) {
        if (clientRequestId != null && !clientRequestId.isBlank() && hasClientRequestId(tenantId, userId, clientRequestId)) {
            throw new DuplicateRequestException();
        }
    }

    private boolean hasClientRequestId(String tenantId, String userId, String clientRequestId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM agent_chat_message m
                JOIN agent_conversation c ON c.id = m.conversation_id
                WHERE m.client_request_id = ? AND c.tenant_id = ? AND c.user_id = ?
                """, Integer.class, clientRequestId, tenantId, userId);
        return count != null && count > 0;
    }

    public void completeUserTurn(String tenantId, String userId, long userMessageId) {
        jdbcTemplate.update("""
                UPDATE agent_chat_message m SET status = 'COMPLETED'
                FROM agent_conversation c
                WHERE m.conversation_id = c.id AND m.id = ? AND c.tenant_id = ? AND c.user_id = ?
                  AND m.status IN ('IN_PROGRESS', 'INTERRUPTED')
                """, userMessageId, tenantId, userId);
    }

    public void markUserTurnInterrupted(String tenantId, String userId, long userMessageId) {
        jdbcTemplate.update("""
                UPDATE agent_chat_message m SET status = 'INTERRUPTED'
                FROM agent_conversation c
                WHERE m.conversation_id = c.id AND m.id = ? AND c.tenant_id = ? AND c.user_id = ?
                  AND m.status = 'IN_PROGRESS'
                """, userMessageId, tenantId, userId);
    }

    /**
     * 会话租约保证同一会话同时最多只有一个执行轮次，因此新轮次开始前仍处于
     * IN_PROGRESS 的 USER 消息一定是上一个进程异常退出后遗留的，需要显式收口，
     * 否则它会永久占据“正在处理”状态并被下一轮当成正常历史。
     */
    public int markStaleUserTurnsInterrupted(String tenantId, String userId, String agentKey, String conversationId) {
        return jdbcTemplate.update("""
                UPDATE agent_chat_message m SET status = 'INTERRUPTED'
                FROM agent_conversation c
                WHERE m.conversation_id = c.id AND c.id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ?
                  AND m.role = 'USER' AND m.status = 'IN_PROGRESS'
                """, conversationId, tenantId, userId, agentKey);
    }

    public long appendAssistantReply(String tenantId, String userId, String agentKey, String conversationId, long userMessageId,
                                     String content, String status, String executionRunId) {
        return appendAssistantReply(tenantId, userId, agentKey, conversationId, userMessageId, content, status, executionRunId, null, null);
    }

    public boolean saveRagJudgment(String tenantId, String userId, String agentKey, long messageId,
                                   String candidateId, String judgment) {
        if (!List.of("travel", "test", "super").contains(agentKey)) {
            throw new IllegalArgumentException("Unsupported agent key for RAG judgment");
        }
        if (!List.of("RELEVANT", "PARTIAL", "IRRELEVANT").contains(judgment)) {
            throw new IllegalArgumentException("Unsupported RAG relevance judgment");
        }
        return jdbcTemplate.update("""
                UPDATE agent_chat_message m
                SET rag_retrieval_trace = COALESCE(m.rag_retrieval_trace, '{}'::jsonb)
                    || jsonb_build_object('judgments',
                        COALESCE(m.rag_retrieval_trace -> 'judgments', '{}'::jsonb)
                        || jsonb_build_object(?, ?::text))
                FROM agent_conversation c
                WHERE m.conversation_id = c.id AND m.id = ? AND m.role = 'ASSISTANT'
                  AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ?
                  AND m.rag_retrieval_trace -> 'candidates' @> jsonb_build_array(jsonb_build_object('documentId', ?))
                """, candidateId, judgment, messageId, tenantId, userId, agentKey, candidateId) == 1;
    }

    public long appendAssistantReply(String tenantId, String userId, String agentKey, String conversationId, long userMessageId,
                                     String content, String status, String executionRunId, String ragRetrievalTrace) {
        return appendAssistantReply(tenantId, userId, agentKey, conversationId, userMessageId, content, status,
                executionRunId, ragRetrievalTrace, null);
    }

    public long appendAssistantReply(String tenantId, String userId, String agentKey, String conversationId, long userMessageId,
                                     String content, String status, String executionRunId, String ragRetrievalTrace,
                                     String fileAttachments) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO agent_chat_message (conversation_id, role, content, status, execution_run_id,
                                                rag_retrieval_trace, file_attachments)
                SELECT id, 'ASSISTANT', ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb) FROM agent_conversation
                WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?
                RETURNING id
                """, Long.class, content == null ? "" : content, status, executionRunId,
                ragRetrievalTrace == null ? "{}" : ragRetrievalTrace,
                fileAttachments == null ? "[]" : fileAttachments, conversationId, tenantId, userId, agentKey);
        if (id == null) throw new IllegalArgumentException("Agent conversation was not found");
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
        return id;
    }

    public boolean updateAssistantReply(String tenantId, String userId, String agentKey, String conversationId,
                                        long assistantMessageId, String content, String status) {
        return updateAssistantReply(tenantId, userId, agentKey, conversationId, assistantMessageId, content, status, null);
    }

    public boolean updateAssistantReply(String tenantId, String userId, String agentKey, String conversationId,
                                        long assistantMessageId, String content, String status, String fileAttachments) {
        int updated = jdbcTemplate.update("""
                UPDATE agent_chat_message m SET content = ?, status = ?,
                    file_attachments = COALESCE(CAST(? AS jsonb), file_attachments)
                FROM agent_conversation c
                WHERE m.id = ? AND m.conversation_id = c.id AND c.id = ? AND c.tenant_id = ? AND c.user_id = ?
                  AND c.agent_key = ? AND m.role = 'ASSISTANT'
                """, content == null ? "" : content, status, fileAttachments, assistantMessageId, conversationId,
                tenantId, userId, agentKey);
        if (updated > 0) {
            jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
        }
        return updated > 0;
    }

    public static class DuplicateRequestException extends RuntimeException {
        public DuplicateRequestException() {
            super("Duplicate request: this message turn is already being processed");
        }
    }

    public void appendMessage(String tenantId, String userId, String agentKey, String conversationId, String role, String content) {
        int inserted = jdbcTemplate.update("INSERT INTO agent_chat_message (conversation_id, role, content) SELECT id, ?, ? FROM agent_conversation WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?",
                role, content == null ? "" : content, conversationId, tenantId, userId, agentKey);
        if (inserted == 0) throw new IllegalArgumentException("Agent conversation was not found");
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?",
                conversationId, tenantId, userId, agentKey);
    }

    public List<AgentConversationSummary> listConversations(String tenantId, String userId, String agentKey) {
        return jdbcTemplate.query("SELECT id, title, created_at, updated_at FROM agent_conversation WHERE tenant_id = ? AND user_id = ? AND agent_key = ? ORDER BY updated_at DESC",
                (rs, rowNum) -> new AgentConversationSummary(rs.getString("id"), rs.getString("title"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
                tenantId, userId, agentKey);
    }

    public List<AgentConversationMessage> getMessages(String tenantId, String userId, String agentKey, String conversationId) {
        requireOwned(tenantId, userId, agentKey, conversationId);
        return jdbcTemplate.query("SELECT m.id, m.role, m.content, m.execution_run_id, m.file_attachments::text AS file_attachments, m.rag_retrieval_trace::text AS rag_retrieval_trace, m.image_object_keys::text AS image_object_keys, m.created_at, m.status FROM agent_chat_message m JOIN agent_conversation c ON c.id = m.conversation_id WHERE c.id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ? ORDER BY m.id ASC",
                (rs, rowNum) -> new AgentConversationMessage(rs.getLong("id"), rs.getString("role"), rs.getString("content"), rs.getString("execution_run_id"), rs.getString("file_attachments"), rs.getString("rag_retrieval_trace"), rs.getTimestamp("created_at").toInstant(), rs.getString("status"), rs.getString("image_object_keys")),
                conversationId, tenantId, userId, agentKey);
    }

    public List<AgentConversationMessage> getRecentMessages(String tenantId, String userId, String agentKey, String conversationId, int limit) {
        List<AgentConversationMessage> messages = getMessages(tenantId, userId, agentKey, conversationId);
        return messages.size() <= limit ? messages : messages.subList(messages.size() - limit, messages.size());
    }

    public void deleteConversation(String tenantId, String userId, String agentKey, String conversationId) {
        requireOwned(tenantId, userId, agentKey, conversationId);
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?",
                conversationId, tenantId, userId, agentKey);
    }

    public Long findAssistantReplyId(String tenantId, String userId, String agentKey, String conversationId, long userMessageId) {
        List<Long> replies = jdbcTemplate.query("""
                SELECT reply.id FROM agent_chat_message reply
                JOIN agent_conversation c ON c.id = reply.conversation_id
                WHERE reply.conversation_id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ?
                  AND reply.role = 'ASSISTANT' AND reply.id > ?
                  AND NOT EXISTS (SELECT 1 FROM agent_chat_message later_user
                                  WHERE later_user.conversation_id = reply.conversation_id AND later_user.role = 'USER'
                                    AND later_user.id > ? AND later_user.id < reply.id)
                ORDER BY reply.id LIMIT 1
                """, (rs, rowNum) -> rs.getLong("id"), conversationId, tenantId, userId, agentKey,
                userMessageId, userMessageId);
        return replies.isEmpty() ? null : replies.getFirst();
    }

    public String deleteAssistantReply(String tenantId, String userId, String agentKey, String conversationId, long userMessageId) {
        requireOwned(tenantId, userId, agentKey, conversationId);
        List<Long> replies = jdbcTemplate.query("SELECT reply.id FROM agent_chat_message reply JOIN agent_conversation c ON c.id = reply.conversation_id WHERE reply.conversation_id = ? AND c.tenant_id = ? AND c.user_id = ? AND c.agent_key = ? AND reply.role = 'ASSISTANT' AND reply.id > ? ORDER BY reply.id LIMIT 1",
                (rs, rowNum) -> rs.getLong("id"), conversationId, tenantId, userId, agentKey, userMessageId);
        if (replies.isEmpty()) return null;
        jdbcTemplate.update("DELETE FROM agent_chat_message WHERE id = ?", replies.getFirst());
        return "deleted";
    }

    private void requireOwned(String tenantId, String userId, String agentKey, String conversationId) {
        if (!hasConversation(tenantId, userId, agentKey, conversationId)) {
            throw new AccessDeniedException("Conversation does not belong to the current user");
        }
    }

    private void requireConversationId(String conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("Conversation id is required");
        }
        try {
            UUID.fromString(conversationId);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Conversation id must be a UUID");
        }
    }

    private AgentConversationSummary getTravelConversation(String tenantId, String userId, String id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, title, created_at, updated_at FROM agent_conversation WHERE id = ? AND tenant_id = ? AND user_id = ? AND agent_key = ?
                """, (rs, rowNum) -> new AgentConversationSummary(rs.getString("id"), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id, tenantId, userId, TRAVEL);
    }

    private String toTitle(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? DEFAULT_TITLE : normalized.length() <= 32 ? normalized : normalized.substring(0, 32) + "...";
    }

    private String title(String message, String defaultTitle) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        return normalized.isEmpty() ? defaultTitle : normalized.length() <= 32 ? normalized : normalized.substring(0, 32) + "...";
    }

    private record TravelReply(long id, String executionRunId) {}
}
