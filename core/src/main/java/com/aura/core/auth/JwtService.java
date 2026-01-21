package com.aura.core.auth;

import com.aura.core.config.AuraProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and validates HS256 JWTs. Tokens are accepted by both the Java core and the Go gateway
 * (the gateway validates them with the same shared secret from AURA_JWT_SECRET).
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final long ttlSeconds;

    public JwtService(AuraProperties props) {
        byte[] secretBytes = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "AURA_JWT_SECRET must be at least 32 characters (got " + secretBytes.length + ")");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.issuer = props.jwt().issuer();
        this.ttlSeconds = props.jwt().ttlSeconds();
    }

    public String issue(UUID userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claims(Map.of("email", email, "role", role))
                .issuedAt(java.util.Date.from(now))
                .expiration(java.util.Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key)
                .compact();
    }

    public Claims verify(String token) {
        Jws<Claims> parsed = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token);
        return parsed.getPayload();
    }
}
