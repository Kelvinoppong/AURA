package com.aura.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class AgentTraceRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentTraceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(
            UUID id,
            UUID conversationId,
            UUID messageId,
            String agentName,
            String model,
            int promptTokens,
            int outputTokens,
            long latencyMs,
            String status,
            String payloadJson,
            OffsetDateTime createdAt) {}

    private static final RowMapper<Row> ROW_MAPPER = (rs, i) -> new Row(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("conversation_id"),
            (UUID) rs.getObject("message_id"),
            rs.getString("agent_name"),
            rs.getString("model"),
            rs.getInt("prompt_tokens"),
            rs.getInt("output_tokens"),
            rs.getLong("latency_ms"),
            rs.getString("status"),
            rs.getString("payload"),
            rs.getObject("created_at", OffsetDateTime.class));

    public void record(UUID conversationId, UUID messageId, AgentExecution<?> e, String status, Object payload) {
        String payloadJson;
        try {
            payloadJson = payload == null ? "{}" : mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            payloadJson = "{}";
        }
        jdbc.update(
                """
                INSERT INTO agent_traces (id, conversation_id, message_id, agent_name, model,
                                          prompt_tokens, output_tokens, latency_ms, status, payload)
                VALUES (:id, :conv, :msg, :agent, :model, :in, :out, :lat, :status, CAST(:payload AS JSONB))
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("conv", conversationId)
                        .addValue("msg", messageId)
                        .addValue("agent", e.agentName())
                        .addValue("model", e.model())
                        .addValue("in", e.promptTokens())
                        .addValue("out", e.outputTokens())
                        .addValue("lat", e.latencyMs())
                        .addValue("status", status)
                        .addValue("payload", payloadJson));
    }

    public List<Row> listByConversation(UUID conversationId, int limit) {
        return jdbc.query(
                """
                SELECT id, conversation_id, message_id, agent_name, model,
                       prompt_tokens, output_tokens, latency_ms, status, payload::text AS payload, created_at
                FROM agent_traces
                WHERE conversation_id = :conv
                ORDER BY created_at ASC
                LIMIT :limit
                """,
                Map.of("conv", conversationId, "limit", limit),
                ROW_MAPPER);
    }
}
