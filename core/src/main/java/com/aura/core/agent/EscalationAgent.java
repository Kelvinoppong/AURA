package com.aura.core.agent;

import com.aura.core.ticket.Ticket;
import com.aura.core.ticket.TicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Creates a human-handoff ticket when the QA agent rejects a draft or the user's priority
 * is "urgent". Deterministic -- no LLM call needed.
 */
@Component
public class EscalationAgent {

    public static final String NAME = "escalate";
    private static final Logger log = LoggerFactory.getLogger(EscalationAgent.class);

    private final TicketService tickets;

    public EscalationAgent(TicketService tickets) {
        this.tickets = tickets;
    }

    public AgentExecution<Ticket> escalate(UUID conversationId, String userMessage, Classification c, String reason) {
        long t0 = System.currentTimeMillis();
        Ticket t = tickets.createFromEscalation(conversationId, userMessage, c.category(), c.priority(), reason);
        log.info("Escalated conversation {} -> ticket {} ({})", conversationId, t.id(), reason);
        long latency = System.currentTimeMillis() - t0;
        return new AgentExecution<>(NAME, "n/a", 0, 0, latency, t);
    }
}
