package com.zwx.zwxagent.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import com.zwx.zwxagent.rag.LoveRagTrace;
import com.zwx.zwxagent.app.LoveVisionAnalysis;
import com.zwx.zwxagent.security.CurrentActor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
public class LoveConversationService {

    private static final String DEFAULT_TITLE = "新的恋爱对话";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LoveConversationService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public LoveConversationSummary createConversation(CurrentActor actor) {
        String conversationId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO love_conversation (id, title, tenant_id, user_id) VALUES (?, ?, ?, ?)",
                conversationId, DEFAULT_TITLE, actor.tenantId(), actor.userId());
        return getConversation(conversationId);
    }

    public void ensureOwnedConversation(CurrentActor actor, String conversationId, String firstMessage) {
        requireConversationId(conversationId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM love_conversation WHERE id = ? AND tenant_id = ? AND user_id = ?",
                Integer.class, conversationId, actor.tenantId(), actor.userId());
        if (count != null && count > 0) {
            touchConversation(conversationId);
            return;
        }
        Integer anyOwner = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM love_conversation WHERE id = ?", Integer.class, conversationId);
        if (anyOwner != null && anyOwner > 0) {
            throw new AccessDeniedException("Conversation does not belong to the current user");
        }
        String title = toTitle(firstMessage);
        jdbcTemplate.update("INSERT INTO love_conversation (id, title, tenant_id, user_id) VALUES (?, ?, ?, ?)",
                conversationId, title, actor.tenantId(), actor.userId());
    }

    public boolean ownsConversation(CurrentActor actor, String conversationId) {
        if (conversationId == null) return false;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM love_conversation WHERE id = ? AND tenant_id = ? AND user_id = ?",
                Integer.class, conversationId, actor.tenantId(), actor.userId());
        return count != null && count > 0;
    }

    public void appendMessage(String conversationId, String role, String content) {
        appendMessage(conversationId, role, content, List.of());
    }

    public void appendMessage(String conversationId, String role, String content, List<String> imageObjectKeys) {
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement("""
                    INSERT INTO love_chat_message (conversation_id, role, content, image_object_keys)
                    VALUES (?, ?, ?, ?)
                    """);
            statement.setString(1, conversationId);
            statement.setString(2, role);
            statement.setString(3, content == null ? "" : content);
            statement.setArray(4, connection.createArrayOf("text", imageObjectKeys.toArray(String[]::new)));
            return statement;
        });
        touchConversation(conversationId);
    }

    @Transactional
    public long startUserTurn(CurrentActor actor, String conversationId, String message, List<String> imageObjectKeys, String clientRequestId) {
        ensureOwnedConversation(actor, conversationId, message);
        requireNoDuplicateRequest(actor, conversationId, clientRequestId);
        List<String> keys = imageObjectKeys == null ? List.of() : imageObjectKeys;
        jdbcTemplate.update(connection -> {
            var statement = connection.prepareStatement(
                    "INSERT INTO love_chat_message (conversation_id, role, content, image_object_keys, status, client_request_id) VALUES (?, 'USER', ?, ?, 'IN_PROGRESS', ?)");
            statement.setString(1, conversationId);
            statement.setString(2, message == null ? "" : message);
            statement.setArray(3, connection.createArrayOf("text", keys.toArray(String[]::new)));
            statement.setString(4, clientRequestId);
            return statement;
        });
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM love_chat_message WHERE conversation_id = ? AND role = 'USER' ORDER BY id DESC LIMIT 1",
                Long.class, conversationId);
        touchConversation(conversationId);
        return id == null ? 0 : id;
    }

    public void requireNoDuplicateRequest(CurrentActor actor, String conversationId, String clientRequestId) {
        if (clientRequestId != null && !clientRequestId.isBlank() && hasClientRequestId(actor, conversationId, clientRequestId)) {
            throw new DuplicateRequestException();
        }
    }

    private boolean hasClientRequestId(CurrentActor actor, String conversationId, String clientRequestId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM love_chat_message m
                JOIN love_conversation c ON c.id = m.conversation_id
                WHERE m.client_request_id = ? AND c.tenant_id = ? AND c.user_id = ?
                """, Integer.class, clientRequestId, actor.tenantId(), actor.userId());
        return count != null && count > 0;
    }

    public void completeUserTurn(CurrentActor actor, long userMessageId, String visionAnalysisJson) {
        jdbcTemplate.update("""
                UPDATE love_chat_message m SET status = 'COMPLETED', vision_analysis = CAST(? AS jsonb)
                FROM love_conversation c
                WHERE m.conversation_id = c.id AND m.id = ? AND c.tenant_id = ? AND c.user_id = ?
                  AND m.status = 'IN_PROGRESS'
                """, visionAnalysisJson, userMessageId, actor.tenantId(), actor.userId());
    }

    public void markUserTurnInterrupted(CurrentActor actor, long userMessageId) {
        jdbcTemplate.update("""
                UPDATE love_chat_message m SET status = 'INTERRUPTED'
                FROM love_conversation c
                WHERE m.conversation_id = c.id AND m.id = ? AND c.tenant_id = ? AND c.user_id = ?
                  AND m.status = 'IN_PROGRESS'
                """, userMessageId, actor.tenantId(), actor.userId());
    }

    @Transactional
    public long appendAssistantReply(CurrentActor actor, String conversationId, long userMessageId, String content,
                                     String status, String referencesJson, String traceJson) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO love_chat_message (conversation_id, role, content, status, knowledge_references, rag_trace)
                VALUES (?, 'ASSISTANT', ?, ?, CAST(? AS jsonb), CAST(? AS jsonb))
                RETURNING id
                """, Long.class, conversationId, content == null ? "" : content, status,
                referencesJson == null ? "[]" : referencesJson, traceJson);
        touchConversation(conversationId);
        return id == null ? 0 : id;
    }

    public static class DuplicateRequestException extends RuntimeException {
        public DuplicateRequestException() {
            super("Duplicate request: this message turn is already being processed");
        }
    }

    public List<LoveConversationSummary> listConversations(CurrentActor actor) {
        return jdbcTemplate.query("""
                        SELECT id, title, created_at, updated_at
                        FROM love_conversation
                        WHERE tenant_id = ? AND user_id = ?
                        ORDER BY updated_at DESC
                        """,
                (rs, rowNum) -> new LoveConversationSummary(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                actor.tenantId(), actor.userId());
    }

    public void saveLatestAssistantRagData(CurrentActor actor, String conversationId, String referencesJson, String traceJson) {
        jdbcTemplate.update("""
                        UPDATE love_chat_message
                        SET knowledge_references = CAST(? AS jsonb), rag_trace = CAST(? AS jsonb)
                        WHERE id = (
                            SELECT m.id FROM love_chat_message m
                            JOIN love_conversation c ON c.id = m.conversation_id
                            WHERE m.conversation_id = ? AND m.role = 'ASSISTANT'
                              AND c.tenant_id = ? AND c.user_id = ?
                            ORDER BY m.id DESC
                            LIMIT 1
                        )
                        """, referencesJson, traceJson, conversationId, actor.tenantId(), actor.userId());
    }

    @Transactional
    public void saveCompletedTurn(CurrentActor actor, String conversationId, String message, List<String> imageObjectKeys, String answer,
                                  String referencesJson, String traceJson, String visionAnalysisJson) {
        ensureOwnedConversation(actor, conversationId, message);
        appendMessage(conversationId, "USER", message, imageObjectKeys);
        if (visionAnalysisJson != null) {
            jdbcTemplate.update("""
                            UPDATE love_chat_message
                            SET vision_analysis = CAST(? AS jsonb)
                            WHERE id = (
                                SELECT m.id FROM love_chat_message m
                                JOIN love_conversation c ON c.id = m.conversation_id
                                WHERE m.conversation_id = ? AND m.role = 'USER'
                                  AND c.tenant_id = ? AND c.user_id = ?
                                ORDER BY m.id DESC
                                LIMIT 1
                            )
                            """, visionAnalysisJson, conversationId, actor.tenantId(), actor.userId());
        }
        appendMessage(conversationId, "ASSISTANT", answer);
        saveLatestAssistantRagData(actor, conversationId, referencesJson, traceJson);
    }

    public List<LoveConversationMessage> getMessages(CurrentActor actor, String conversationId) {
        requireOwnedConversation(actor, conversationId);
        return jdbcTemplate.query("""
                        SELECT id, role, content, image_object_keys, knowledge_references, rag_trace, vision_analysis, created_at FROM (
                            SELECT id, role, content, image_object_keys, knowledge_references, rag_trace, vision_analysis, created_at
                            FROM love_chat_message
                            WHERE conversation_id = ?
                            ORDER BY id DESC
                            LIMIT 500
                        ) recent_messages
                        ORDER BY id ASC
                        """, (rs, rowNum) -> new LoveConversationMessage(
                        rs.getLong("id"), rs.getString("role"),
                        rs.getString("content"),
                        toStringList(rs.getArray("image_object_keys")),
                        toReferences(rs.getString("knowledge_references")),
                        toTrace(rs.getString("rag_trace")),
                        toVisionAnalysis(rs.getString("vision_analysis")),
                        rs.getTimestamp("created_at").toInstant()), conversationId);
    }

    public List<LoveConversationMessage> getRecentMessages(String conversationId, int limit) {
        return jdbcTemplate.query("""
                        SELECT id, role, content, image_object_keys, knowledge_references, rag_trace, vision_analysis, created_at FROM (
                            SELECT id, role, content, image_object_keys, knowledge_references, rag_trace, vision_analysis, created_at
                            FROM love_chat_message
                            WHERE conversation_id = ? AND status = 'COMPLETED'
                            ORDER BY id DESC
                            LIMIT ?
                        ) recent_messages
                        ORDER BY id ASC
                        """, (rs, rowNum) -> new LoveConversationMessage(
                        rs.getLong("id"), rs.getString("role"),
                        rs.getString("content"),
                        toStringList(rs.getArray("image_object_keys")),
                        toReferences(rs.getString("knowledge_references")),
                        toTrace(rs.getString("rag_trace")),
                        toVisionAnalysis(rs.getString("vision_analysis")),
                        rs.getTimestamp("created_at").toInstant()), conversationId, limit);
    }

    public boolean deleteConversation(CurrentActor actor, String conversationId) {
        requireOwnedConversation(actor, conversationId);
        return jdbcTemplate.update("DELETE FROM love_conversation WHERE id = ? AND tenant_id = ? AND user_id = ?",
                conversationId, actor.tenantId(), actor.userId()) > 0;
    }

    public boolean deleteAssistantReply(CurrentActor actor, String conversationId, long userMessageId) {
        requireOwnedConversation(actor, conversationId);
        return jdbcTemplate.update("""
                DELETE FROM love_chat_message
                WHERE id = (
                    SELECT reply.id FROM love_chat_message reply
                    WHERE reply.conversation_id = ? AND reply.role = 'ASSISTANT' AND reply.id > ?
                      AND NOT EXISTS (SELECT 1 FROM love_chat_message later_user
                                      WHERE later_user.conversation_id = ? AND later_user.role = 'USER'
                                        AND later_user.id > ? AND later_user.id < reply.id)
                    ORDER BY reply.id LIMIT 1
                )
                """, conversationId, userMessageId, conversationId, userMessageId) > 0;
    }

    private void requireOwnedConversation(CurrentActor actor, String conversationId) {
        if (!ownsConversation(actor, conversationId)) {
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

    private void touchConversation(String conversationId) {
        jdbcTemplate.update("UPDATE love_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
    }

    private LoveConversationSummary getConversation(String conversationId) {
        return jdbcTemplate.queryForObject("""
                        SELECT id, title, created_at, updated_at
                        FROM love_conversation
                        WHERE id = ?
                        """, (rs, rowNum) -> new LoveConversationSummary(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()), conversationId);
    }

    private String toTitle(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return DEFAULT_TITLE;
        }
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32) + "...";
    }

    private List<String> toStringList(Array sqlArray) throws java.sql.SQLException {
        if (sqlArray == null) {
            return List.of();
        }
        return Arrays.asList((String[]) sqlArray.getArray());
    }

    private List<LoveKnowledgeReference> toReferences(String referencesJson) {
        if (referencesJson == null || referencesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(referencesJson, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read persisted knowledge references", exception);
        }
    }

    private LoveRagTrace toTrace(String traceJson) {
        if (traceJson == null || traceJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(traceJson, LoveRagTrace.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read persisted RAG trace", exception);
        }
    }

    private LoveVisionAnalysis toVisionAnalysis(String analysisJson) {
        if (analysisJson == null || analysisJson.isBlank()) return null;
        try {
            return objectMapper.readValue(analysisJson, LoveVisionAnalysis.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read persisted vision analysis", exception);
        }
    }
}
