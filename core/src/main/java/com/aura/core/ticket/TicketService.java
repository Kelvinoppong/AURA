package com.aura.core.ticket;

import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TicketService {

    private final TicketRepository repo;

    public TicketService(TicketRepository repo) {
        this.repo = repo;
    }

    public Ticket create(String subject, String body, String category, String priority, String customerEmail) {
        Ticket t = new Ticket(
                UUID.randomUUID(),
                subject,
                body,
                "open",
                category,
                priority == null ? "normal" : priority,
                customerEmail,
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null);
        return repo.insert(t);
    }

    public Ticket createFromEscalation(UUID conversationId, String userMessage, String category, String priority, String reason) {
        String subject = userMessage.length() > 80 ? userMessage.substring(0, 80) + "..." : userMessage;
        String body = """
                Escalated from conversation %s.

                Reason: %s

                Original message:
                %s
                """.formatted(conversationId, reason, userMessage);
        Ticket t = new Ticket(
                UUID.randomUUID(),
                subject,
                body,
                "escalated",
                category,
                priority,
                null,
                null,
                conversationId,
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                null);
        return repo.insert(t);
    }

    public Optional<Ticket> byId(UUID id) {
        return repo.findById(id);
    }

    public List<Ticket> list(String status, int limit) {
        return repo.list(status, limit);
    }

    public Ticket updateStatus(UUID id, String status) {
        repo.updateStatus(id, status);
        return repo.findById(id).orElseThrow();
    }
}
