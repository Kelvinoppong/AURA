package com.aura.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration for AURA. Bound from application.yml under the `aura` prefix.
 */
@ConfigurationProperties(prefix = "aura")
public record AuraProperties(
        Jwt jwt,
        Gemini gemini,
        Embeddings embeddings,
        Rag rag,
        Orchestrator orchestrator
) {
    public record Jwt(String secret, String issuer, long ttlSeconds) {}

    public record Gemini(String apiKey, String fastModel, String reasoningModel, int timeoutSeconds) {}

    public record Embeddings(String modelId, int dimensions) {}

    public record Rag(int topK, double minScore) {}

    public record Orchestrator(double qaMinScore, double escalationThreshold) {}
}
