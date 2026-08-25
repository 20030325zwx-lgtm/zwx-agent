package com.zwx.zwxagent.conversation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AgentConversationService {

    private static final String TRAVEL = "travel";
    private static final String DEFAULT_TITLE = "新的旅行规划";
    private final JdbcTemplate jdbcTemplate;

    public AgentConversationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public AgentConversationSummary createTravelConversation(String tenantId) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO agent_conversation (id, tenant_id, agent_key, title) VALUES (?, ?, ?, ?)", id, tenantId, TRAVEL, DEFAULT_TITLE);
        return getTravelConversation(tenantId, id);
    }

    public void ensureTravelConversation(String tenantId, String conversationId, String firstMessage) {
        jdbcTemplate.update("""
                INSERT INTO agent_conversation (id, tenant_id, agent_key, title) VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    title = CASE WHEN agent_conversation.title = ? THEN EXCLUDED.title ELSE agent_conversation.title END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE agent_conversation.tenant_id = EXCLUDED.tenant_id AND agent_conversation.agent_key = EXCLUDED.agent_key
                """, conversationId, tenantId, TRAVEL, toTitle(firstMessage), DEFAULT_TITLE);
    }

    public void appendTravelMessage(String tenantId, String conversationId, String role, String content) {
        appendTravelMessage(tenantId, conversationId, role, content, null);
    }

    public void appendTravelMessage(String tenantId, String conversationId, String role, String content, String executionRunId) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO agent_chat_message (conversation_id, role, content, execution_run_id)
                SELECT id, ?, ?, ? FROM agent_conversation WHERE id = ? AND tenant_id = ? AND agent_key = ?
                """, role, content == null ? "" : content, executionRunId, conversationId, tenantId, TRAVEL);
        if (inserted == 0) throw new IllegalArgumentException("Travel conversation was not found");
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND agent_key = ?", conversationId, tenantId, TRAVEL);
    }

    @Transactional
    public void saveCompletedTravelTurn(String tenantId, String conversationId, String message, String answer, String executionRunId) {
        ensureTravelConversation(tenantId, conversationId, message);
        appendTravelMessage(tenantId, conversationId, "USER", message);
        appendTravelMessage(tenantId, conversationId, "ASSISTANT", answer, executionRunId);
    }

    public List<AgentConversationSummary> listTravelConversations(String tenantId) {
        return jdbcTemplate.query("""
                SELECT id, title, created_at, updated_at FROM agent_conversation
                WHERE tenant_id = ? AND agent_key = ? ORDER BY updated_at DESC
                """, (rs, rowNum) -> new AgentConversationSummary(rs.getString("id"), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), tenantId, TRAVEL);
    }

    public List<AgentConversationMessage> getTravelMessages(String tenantId, String conversationId) {
        return jdbcTemplate.query("""
                SELECT m.id, m.role, m.content, m.execution_run_id, m.file_attachments::text AS file_attachments, m.created_at FROM agent_chat_message m
                JOIN agent_conversation c ON c.id = m.conversation_id
                WHERE c.id = ? AND c.tenant_id = ? AND c.agent_key = ? ORDER BY m.id ASC
                """, (rs, rowNum) -> new AgentConversationMessage(rs.getLong("id"), rs.getString("role"), rs.getString("content"), rs.getString("execution_run_id"),
                rs.getString("file_attachments"), rs.getTimestamp("created_at").toInstant()), conversationId, tenantId, TRAVEL);
    }

    public List<AgentConversationMessage> getRecentTravelMessages(String tenantId, String conversationId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, role, content, execution_run_id, created_at FROM (
                    SELECT m.id, m.role, m.content, m.execution_run_id, m.file_attachments::text AS file_attachments, m.created_at FROM agent_chat_message m
                    JOIN agent_conversation c ON c.id = m.conversation_id
                    WHERE c.id = ? AND c.tenant_id = ? AND c.agent_key = ? ORDER BY m.id DESC LIMIT ?
                ) recent_messages ORDER BY id ASC
                """, (rs, rowNum) -> new AgentConversationMessage(rs.getLong("id"), rs.getString("role"), rs.getString("content"), rs.getString("execution_run_id"),
                rs.getString("file_attachments"), rs.getTimestamp("created_at").toInstant()), conversationId, tenantId, TRAVEL, limit);
    }

    public void deleteTravelConversation(String tenantId, String conversationId) {
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE id = ? AND tenant_id = ? AND agent_key = ?", conversationId, tenantId, TRAVEL);
    }

    public String deleteTravelAssistantReply(String tenantId, String conversationId, long userMessageId) {
        List<TravelReply> replies = jdbcTemplate.query("""
                SELECT reply.id, reply.execution_run_id FROM agent_chat_message reply
                JOIN agent_conversation c ON c.id = reply.conversation_id
                WHERE reply.conversation_id = ? AND c.tenant_id = ? AND c.agent_key = ? AND reply.role = 'ASSISTANT' AND reply.id > ?
                  AND NOT EXISTS (SELECT 1 FROM agent_chat_message later_user
                                  WHERE later_user.conversation_id = reply.conversation_id AND later_user.role = 'USER'
                                    AND later_user.id > ? AND later_user.id < reply.id)
                ORDER BY reply.id LIMIT 1
                """, (rs, rowNum) -> new TravelReply(rs.getLong("id"), rs.getString("execution_run_id")), conversationId, tenantId, TRAVEL, userMessageId, userMessageId);
        if (replies.isEmpty()) return null;
        TravelReply reply = replies.getFirst();
        jdbcTemplate.update("DELETE FROM agent_chat_message WHERE id = ?", reply.id());
        return reply.executionRunId();
    }

    public AgentConversationSummary createConversation(String tenantId, String agentKey, String defaultTitle) {
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO agent_conversation (id, tenant_id, agent_key, title) VALUES (?, ?, ?, ?)", id, tenantId, agentKey, defaultTitle);
        return jdbcTemplate.queryForObject("SELECT id, title, created_at, updated_at FROM agent_conversation WHERE id = ? AND tenant_id = ? AND agent_key = ?",
                (rs, rowNum) -> new AgentConversationSummary(rs.getString("id"), rs.getString("title"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id, tenantId, agentKey);
    }

    public void saveCompletedTurn(String tenantId, String agentKey, String conversationId, String defaultTitle, String message, String answer) {
        jdbcTemplate.update("INSERT INTO agent_conversation (id, tenant_id, agent_key, title) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                conversationId, tenantId, agentKey, title(message, defaultTitle));
        appendMessage(tenantId, agentKey, conversationId, "USER", message);
        appendMessage(tenantId, agentKey, conversationId, "ASSISTANT", answer);
    }

    public void saveCompletedTurn(String tenantId, String agentKey, String conversationId, String defaultTitle, String message, String answer, String fileAttachments) {
        ensureConversation(tenantId, agentKey, conversationId, defaultTitle, message);
        appendMessage(tenantId, agentKey, conversationId, "USER", message);
        int inserted = jdbcTemplate.update("""
                INSERT INTO agent_chat_message (conversation_id, role, content, file_attachments)
                SELECT id, 'ASSISTANT', ?, CAST(? AS jsonb) FROM agent_conversation
                WHERE id = ? AND tenant_id = ? AND agent_key = ?
                """, answer == null ? "" : answer, fileAttachments == null ? "[]" : fileAttachments, conversationId, tenantId, agentKey);
        if (inserted == 0) throw new IllegalArgumentException("Agent conversation was not found");
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND agent_key = ?", conversationId, tenantId, agentKey);
    }

    public void ensureConversation(String tenantId, String agentKey, String conversationId, String defaultTitle, String firstMessage) {
        jdbcTemplate.update("""
                INSERT INTO agent_conversation (id, tenant_id, agent_key, title) VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    title = CASE WHEN agent_conversation.title = ? THEN EXCLUDED.title ELSE agent_conversation.title END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE agent_conversation.tenant_id = EXCLUDED.tenant_id AND agent_conversation.agent_key = EXCLUDED.agent_key
                """, conversationId, tenantId, agentKey, title(firstMessage, defaultTitle), defaultTitle);
    }

    public boolean hasConversation(String tenantId, String agentKey, String conversationId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_conversation WHERE id = ? AND tenant_id = ? AND agent_key = ?", Integer.class, conversationId, tenantId, agentKey);
        return count != null && count > 0;
    }

    public void appendMessage(String tenantId, String agentKey, String conversationId, String role, String content) {
        int inserted = jdbcTemplate.update("INSERT INTO agent_chat_message (conversation_id, role, content) SELECT id, ?, ? FROM agent_conversation WHERE id = ? AND tenant_id = ? AND agent_key = ?",
                role, content == null ? "" : content, conversationId, tenantId, agentKey);
        if (inserted == 0) throw new IllegalArgumentException("Agent conversation was not found");
        jdbcTemplate.update("UPDATE agent_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ? AND tenant_id = ? AND agent_key = ?", conversationId, tenantId, agentKey);
    }

    public List<AgentConversationSummary> listConversations(String tenantId, String agentKey) {
        return jdbcTemplate.query("SELECT id, title, created_at, updated_at FROM agent_conversation WHERE tenant_id = ? AND agent_key = ? ORDER BY updated_at DESC",
                (rs, rowNum) -> new AgentConversationSummary(rs.getString("id"), rs.getString("title"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), tenantId, agentKey);
    }

    public List<AgentConversationMessage> getMessages(String tenantId, String agentKey, String conversationId) {
        return jdbcTemplate.query("SELECT m.id, m.role, m.content, m.execution_run_id, m.file_attachments::text AS file_attachments, m.created_at FROM agent_chat_message m JOIN agent_conversation c ON c.id = m.conversation_id WHERE c.id = ? AND c.tenant_id = ? AND c.agent_key = ? ORDER BY m.id ASC",
                (rs, rowNum) -> new AgentConversationMessage(rs.getLong("id"), rs.getString("role"), rs.getString("content"), rs.getString("execution_run_id"), rs.getString("file_attachments"), rs.getTimestamp("created_at").toInstant()), conversationId, tenantId, agentKey);
    }

    public List<AgentConversationMessage> getRecentMessages(String tenantId, String agentKey, String conversationId, int limit) {
        List<AgentConversationMessage> messages = getMessages(tenantId, agentKey, conversationId);
        return messages.size() <= limit ? messages : messages.subList(messages.size() - limit, messages.size());
    }

    public void deleteConversation(String tenantId, String agentKey, String conversationId) {
        jdbcTemplate.update("DELETE FROM agent_conversation WHERE id = ? AND tenant_id = ? AND agent_key = ?", conversationId, tenantId, agentKey);
    }

    public String deleteAssistantReply(String tenantId, String agentKey, String conversationId, long userMessageId) {
        List<Long> replies = jdbcTemplate.query("SELECT reply.id FROM agent_chat_message reply JOIN agent_conversation c ON c.id = reply.conversation_id WHERE reply.conversation_id = ? AND c.tenant_id = ? AND c.agent_key = ? AND reply.role = 'ASSISTANT' AND reply.id > ? ORDER BY reply.id LIMIT 1", (rs, rowNum) -> rs.getLong("id"), conversationId, tenantId, agentKey, userMessageId);
        if (replies.isEmpty()) return null;
        jdbcTemplate.update("DELETE FROM agent_chat_message WHERE id = ?", replies.getFirst());
        return "deleted";
    }

    private AgentConversationSummary getTravelConversation(String tenantId, String id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, title, created_at, updated_at FROM agent_conversation WHERE id = ? AND tenant_id = ? AND agent_key = ?
                """, (rs, rowNum) -> new AgentConversationSummary(rs.getString("id"), rs.getString("title"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id, tenantId, TRAVEL);
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
