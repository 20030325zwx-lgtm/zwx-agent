package com.zwx.zwxagent.conversation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zwx.zwxagent.rag.LoveKnowledgeReference;
import com.zwx.zwxagent.rag.LoveRagTrace;
import com.zwx.zwxagent.app.LoveVisionAnalysis;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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

    public LoveConversationSummary createConversation() {
        String conversationId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO love_conversation (id, title) VALUES (?, ?)", conversationId, DEFAULT_TITLE);
        return getConversation(conversationId);
    }

    public void ensureConversation(String conversationId, String firstMessage) {
        String title = toTitle(firstMessage);
        jdbcTemplate.update("""
                INSERT INTO love_conversation (id, title) VALUES (?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    title = CASE WHEN love_conversation.title = ? THEN EXCLUDED.title ELSE love_conversation.title END,
                    updated_at = CURRENT_TIMESTAMP
                """, conversationId, title, DEFAULT_TITLE);
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
        jdbcTemplate.update("UPDATE love_conversation SET updated_at = CURRENT_TIMESTAMP WHERE id = ?", conversationId);
    }

    public List<LoveConversationSummary> listConversations() {
        return jdbcTemplate.query("""
                        SELECT id, title, created_at, updated_at
                        FROM love_conversation
                        ORDER BY updated_at DESC
                        """,
                (rs, rowNum) -> new LoveConversationSummary(
                        rs.getString("id"),
                        rs.getString("title"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()));
    }

    public void saveLatestAssistantRagData(String conversationId, String referencesJson, String traceJson) {
        jdbcTemplate.update("""
                        UPDATE love_chat_message
                        SET knowledge_references = CAST(? AS jsonb), rag_trace = CAST(? AS jsonb)
                        WHERE id = (
                            SELECT id FROM love_chat_message
                            WHERE conversation_id = ? AND role = 'ASSISTANT'
                            ORDER BY id DESC
                            LIMIT 1
                        )
                        """, referencesJson, traceJson, conversationId);
    }

    public void saveLatestUserVisionAnalysis(String conversationId, String analysisJson) {
        jdbcTemplate.update("""
                        UPDATE love_chat_message
                        SET vision_analysis = CAST(? AS jsonb)
                        WHERE id = (
                            SELECT id FROM love_chat_message
                            WHERE conversation_id = ? AND role = 'USER'
                            ORDER BY id DESC
                            LIMIT 1
                        )
                        """, analysisJson, conversationId);
    }

    public List<LoveConversationMessage> getMessages(String conversationId) {
        return jdbcTemplate.query("""
                        SELECT role, content, image_object_keys, knowledge_references, rag_trace, vision_analysis, created_at FROM (
                            SELECT id, role, content, image_object_keys, knowledge_references, rag_trace, vision_analysis, created_at
                            FROM love_chat_message
                            WHERE conversation_id = ?
                            ORDER BY id DESC
                            LIMIT 500
                        ) recent_messages
                        ORDER BY id ASC
                        """, (rs, rowNum) -> new LoveConversationMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        toStringList(rs.getArray("image_object_keys")),
                        toReferences(rs.getString("knowledge_references")),
                        toTrace(rs.getString("rag_trace")),
                        toVisionAnalysis(rs.getString("vision_analysis")),
                        rs.getTimestamp("created_at").toInstant()), conversationId);
    }

    public List<LoveConversationMessage> getRecentMessages(String conversationId, int limit) {
        return jdbcTemplate.query("""
                        SELECT role, content, image_object_keys, knowledge_references, rag_trace, vision_analysis, created_at FROM (
                            SELECT id, role, content, image_object_keys, knowledge_references, rag_trace, vision_analysis, created_at
                            FROM love_chat_message
                            WHERE conversation_id = ?
                            ORDER BY id DESC
                            LIMIT ?
                        ) recent_messages
                        ORDER BY id ASC
                        """, (rs, rowNum) -> new LoveConversationMessage(
                        rs.getString("role"),
                        rs.getString("content"),
                        toStringList(rs.getArray("image_object_keys")),
                        toReferences(rs.getString("knowledge_references")),
                        toTrace(rs.getString("rag_trace")),
                        toVisionAnalysis(rs.getString("vision_analysis")),
                        rs.getTimestamp("created_at").toInstant()), conversationId, limit);
    }

    public boolean deleteConversation(String conversationId) {
        return jdbcTemplate.update("DELETE FROM love_conversation WHERE id = ?", conversationId) > 0;
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
