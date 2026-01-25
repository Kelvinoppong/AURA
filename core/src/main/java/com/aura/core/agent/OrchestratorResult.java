package com.aura.core.agent;

import com.aura.core.rag.RetrievedChunk;
import com.aura.core.ticket.Ticket;

import java.util.List;
import java.util.UUID;

/**
 * Final result of one turn through the orchestrator state machine.
 */
public record OrchestratorResult(
        UUID conversationId,
        UUID assistantMessageId,
        String reply,
        Classification classification,
        List<RetrievedChunk> retrieved,
        QaVerdict qa,
        Ticket escalatedTicket,
        long totalLatencyMs,
        List<TraceStep> trace
) {
    public record TraceStep(String agent, String model, int promptTokens, int outputTokens, long latencyMs, String status) {}
}
