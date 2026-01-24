package com.aura.core.ticket;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Ticket(
        UUID id,
        String subject,
        String body,
        String status,
        String category,
        String priority,
        String customerEmail,
        UUID assigneeId,
        UUID conversationId,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime resolvedAt
) {}
