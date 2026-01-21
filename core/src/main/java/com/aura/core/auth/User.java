package com.aura.core.auth;

import java.time.OffsetDateTime;
import java.util.UUID;

public record User(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        String role,
        OffsetDateTime createdAt
) {}
