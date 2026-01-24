package com.aura.core.agent;

/**
 * A single agent's execution record. Captures enough to build an {@code agent_traces}
 * row for observability + the resume's "per-turn trace viewer" story.
 */
public record AgentExecution<T>(
        String agentName,
        String model,
        int promptTokens,
        int outputTokens,
        long latencyMs,
        T output
) {}
