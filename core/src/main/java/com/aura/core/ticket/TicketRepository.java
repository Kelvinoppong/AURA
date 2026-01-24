package com.aura.core.ticket;

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
public class TicketRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TicketRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Ticket> ROW_MAPPER = (rs, i) -> new Ticket(
            (UUID) rs.getObject("id"),
            rs.getString("subject"),
            rs.getString("body"),
            rs.getString("status"),
            rs.getString("category"),
            rs.getString("priority"),
            rs.getString("customer_email"),
            (UUID) rs.getObject("assignee_id"),
            (UUID) rs.getObject("conversation_id"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("updated_at", OffsetDateTime.class),
            rs.getObject("resolved_at", OffsetDateTime.class));

    public Ticket insert(Ticket t) {
        jdbc.update(
                """
                INSERT INTO tickets (id, subject, body, status, category, priority, customer_email,
                                     assignee_id, conversation_id, created_at, updated_at)
                VALUES (:id, :subject, :body, :status, :category, :priority, :email,
                        :assignee, :conv, :created, :updated)
                """,
                new MapSqlParameterSource()
                        .addValue("id", t.id())
                        .addValue("subject", t.subject())
                        .addValue("body", t.body())
                        .addValue("status", t.status())
                        .addValue("category", t.category())
                        .addValue("priority", t.priority())
                        .addValue("email", t.customerEmail())
                        .addValue("assignee", t.assigneeId())
                        .addValue("conv", t.conversationId())
                        .addValue("created", t.createdAt())
                        .addValue("updated", t.updatedAt()));
        return t;
    }

    public Optional<Ticket> findById(UUID id) {
        return jdbc.query("SELECT * FROM tickets WHERE id = :id", Map.of("id", id), ROW_MAPPER)
                .stream().findFirst();
    }

    public List<Ticket> list(String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.query("SELECT * FROM tickets ORDER BY created_at DESC LIMIT :limit",
                    Map.of("limit", limit), ROW_MAPPER);
        }
        return jdbc.query(
                "SELECT * FROM tickets WHERE status = :status ORDER BY created_at DESC LIMIT :limit",
                Map.of("status", status, "limit", limit),
                ROW_MAPPER);
    }

    public int updateStatus(UUID id, String status) {
        return jdbc.update(
                "UPDATE tickets SET status = :status, updated_at = now(), " +
                        "resolved_at = CASE WHEN :status = 'resolved' THEN now() ELSE resolved_at END " +
                        "WHERE id = :id",
                Map.of("id", id, "status", status));
    }
}
