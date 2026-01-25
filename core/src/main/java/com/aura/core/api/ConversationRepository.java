package com.aura.core.api;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ConversationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Conversation(UUID id, UUID userId, String title, UUID ticketId,
                               OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    public record Message(UUID id, UUID conversationId, String role, String content, OffsetDateTime createdAt) {}

    private static final RowMapper<Conversation> CONV_MAPPER = (rs, i) -> new Conversation(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("user_id"),
            rs.getString("title"),
            (UUID) rs.getObject("ticket_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class));

    private static final RowMapper<Message> MSG_MAPPER = (rs, i) -> new Message(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("conversation_id"),
            rs.getString("role"),
            rs.getString("content"),
            rs.getObject("created_at", OffsetDateTime.class));

    public Conversation create(UUID userId, String title) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                """
                INSERT INTO conversations (id, user_id, title, created_at, updated_at)
                VALUES (:id, :user, :title, :now, :now)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("user", userId)
                        .addValue("title", title == null ? "New conversation" : title)
                        .addValue("now", now));
        return new Conversation(id, userId, title, null, now, now);
    }

    public Optional<Conversation> byId(UUID id) {
        return jdbc.query("SELECT * FROM conversations WHERE id = :id", Map.of("id", id), CONV_MAPPER)
                .stream().findFirst();
    }

    public List<Conversation> listForUser(UUID userId, int limit) {
        return jdbc.query(
                "SELECT * FROM conversations WHERE user_id = :user ORDER BY updated_at DESC LIMIT :limit",
                Map.of("user", userId, "limit", limit),
                CONV_MAPPER);
    }

    public Message appendMessage(UUID conversationId, String role, String content) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                """
                INSERT INTO messages (id, conversation_id, role, content, created_at)
                VALUES (:id, :conv, :role, :content, :now)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("conv", conversationId)
                        .addValue("role", role)
                        .addValue("content", content)
                        .addValue("now", now));
        jdbc.update("UPDATE conversations SET updated_at = :now WHERE id = :id",
                Map.of("id", conversationId, "now", now));
        return new Message(id, conversationId, role, content, now);
    }

    public List<Message> messages(UUID conversationId) {
        return jdbc.query(
                "SELECT * FROM messages WHERE conversation_id = :conv ORDER BY created_at ASC",
                Map.of("conv", conversationId),
                MSG_MAPPER);
    }
}
