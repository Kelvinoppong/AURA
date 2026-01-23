package com.aura.core.rag;

import com.pgvector.PGvector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Direct pgvector access. Reads & writes {@code vector(384)} columns using the
 * pgvector JDBC helper. Cosine distance is used via the {@code <=>} operator to
 * match the HNSW index defined in V1__schema.sql.
 */
@Repository
public class VectorStore {

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate jdbcPlain;

    public VectorStore(NamedParameterJdbcTemplate jdbc, DataSource ds) {
        this.jdbc = jdbc;
        this.jdbcPlain = new JdbcTemplate(ds);
        // Register pgvector type on every borrowed connection.
        try (Connection conn = ds.getConnection()) {
            PGvector.addVectorType(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot register pgvector type", e);
        }
    }

    public UUID upsertDoc(String title, String source, String metadataJson) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO knowledge_docs (id, title, source, metadata)
                VALUES (:id, :title, :source, CAST(:meta AS JSONB))
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("title", title)
                        .addValue("source", source)
                        .addValue("meta", metadataJson == null ? "{}" : metadataJson));
        return id;
    }

    public void insertChunks(UUID docId, List<String> contents, List<float[]> vectors) {
        if (contents.size() != vectors.size()) {
            throw new IllegalArgumentException("contents/vectors size mismatch");
        }
        jdbcPlain.batchUpdate(
                """
                INSERT INTO knowledge_chunks (id, doc_id, chunk_index, content, embedding)
                VALUES (?, ?, ?, ?, ?::vector)
                """,
                contents.size(),
                (ps, i) -> {
                    ps.setObject(1, UUID.randomUUID());
                    ps.setObject(2, docId);
                    ps.setInt(3, i);
                    ps.setString(4, contents.get(i));
                    ps.setString(5, new PGvector(vectors.get(i)).toString());
                });
    }

    public List<RetrievedChunk> search(float[] queryEmbedding, int topK) {
        String vec = new PGvector(queryEmbedding).toString();
        return jdbc.query(
                """
                SELECT kc.id, kc.doc_id, kd.title, kc.content,
                       1 - (kc.embedding <=> CAST(:q AS vector)) AS score
                FROM knowledge_chunks kc
                JOIN knowledge_docs kd ON kd.id = kc.doc_id
                ORDER BY kc.embedding <=> CAST(:q AS vector)
                LIMIT :k
                """,
                Map.of("q", vec, "k", topK),
                (rs, i) -> new RetrievedChunk(
                        (UUID) rs.getObject("id"),
                        (UUID) rs.getObject("doc_id"),
                        rs.getString("title"),
                        rs.getString("content"),
                        rs.getDouble("score")));
    }

    public long countChunks() {
        Long n = jdbcPlain.queryForObject("SELECT COUNT(*) FROM knowledge_chunks", Long.class);
        return n == null ? 0L : n;
    }
}
