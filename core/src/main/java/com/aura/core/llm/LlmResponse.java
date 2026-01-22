package com.aura.core.llm;

public record LlmResponse(
        String text,
        String model,
        int promptTokens,
        int outputTokens,
        long latencyMs
) {}
