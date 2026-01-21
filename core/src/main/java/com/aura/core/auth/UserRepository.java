package com.aura.core.auth;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<User> ROW_MAPPER = (rs, i) -> new User(
            (UUID) rs.getObject("id"),
            rs.getString("email"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("role"),
            rs.getObject("created_at", OffsetDateTime.class));

    public Optional<User> findByEmail(String email) {
        var results = jdbc.query(
                "SELECT id, email, password_hash, display_name, role, created_at FROM users WHERE email = :email",
                Map.of("email", email),
                ROW_MAPPER);
        return results.stream().findFirst();
    }

    public Optional<User> findById(UUID id) {
        var results = jdbc.query(
                "SELECT id, email, password_hash, display_name, role, created_at FROM users WHERE id = :id",
                Map.of("id", id),
                ROW_MAPPER);
        return results.stream().findFirst();
    }

    public User create(String email, String passwordHash, String displayName, String role) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        jdbc.update(
                """
                INSERT INTO users (id, email, password_hash, display_name, role, created_at)
                VALUES (:id, :email, :hash, :name, :role, :created)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("email", email)
                        .addValue("hash", passwordHash)
                        .addValue("name", displayName)
                        .addValue("role", role)
                        .addValue("created", now));
        return new User(id, email, passwordHash, displayName, role, now);
    }
}
