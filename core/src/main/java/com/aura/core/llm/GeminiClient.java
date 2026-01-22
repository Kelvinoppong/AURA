package com.aura.core.llm;

import com.aura.core.config.AuraProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-light Gemini REST client.
 * <p>
 * Uses Spring's WebClient so we can both do one-shot generation and server-sent streaming
 * (needed for the Go gateway's WebSocket token stream). This intentionally avoids
 * dragging in the full Google SDK -- a single-file, transparent client is easier to demo.
 */
@Component
public class GeminiClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiClient.class);
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta";

    private final org.springframework.web.reactive.function.client.WebClient web;
    private final AuraProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiClient(AuraProperties props) {
        this.props = props;
        this.web = org.springframework.web.reactive.function.client.WebClient.builder()
                .baseUrl(BASE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    public boolean isConfigured() {
        return props.gemini().apiKey() != null && !props.gemini().apiKey().isBlank();
    }

    /**
     * Blocking single-shot generation. Returns text + token accounting.
     */
    public LlmResponse generate(String model, String system, String user, double temperature) {
        if (!isConfigured()) {
            return mockResponse(model, system, user);
        }
        ObjectNode body = buildRequest(system, user, temperature);
        long t0 = System.currentTimeMillis();
        try {
            String raw = web.post()
                    .uri(uriBuilder -> uriBuilder
                            .path("/models/{model}:generateContent")
                            .queryParam("key", props.gemini().apiKey())
                            .build(model))
                    .header("Content-Type", "application/json")
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(props.gemini().timeoutSeconds()))
                    .block();
            long latency = System.currentTimeMillis() - t0;
            return parseResponse(model, raw, latency);
        } catch (Exception e) {
            log.warn("Gemini call failed ({}): {}", model, e.getMessage());
            return new LlmResponse("[gemini-error] " + e.getMessage(), model, 0, 0,
                    System.currentTimeMillis() - t0);
        }
    }

    /**
     * Streaming generation. Emits incremental text chunks as Gemini produces them.
     */
    public Flux<String> stream(String model, String system, String user, double temperature) {
        if (!isConfigured()) {
            return Flux.fromIterable(mockChunks(system, user));
        }
        ObjectNode body = buildRequest(system, user, temperature);
        return web.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/models/{model}:streamGenerateContent")
                        .queryParam("alt", "sse")
                        .queryParam("key", props.gemini().apiKey())
                        .build(model))
                .header("Content-Type", "application/json")
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .mapNotNull(this::extractTextChunk)
                .filter(s -> !s.isEmpty());
    }

    // -------- helpers --------

    private ObjectNode buildRequest(String system, String user, double temperature) {
        ObjectNode body = mapper.createObjectNode();

        var contents = mapper.createArrayNode();
        var userPart = mapper.createObjectNode();
        userPart.put("role", "user");
        userPart.putArray("parts").addObject().put("text", user);
        contents.add(userPart);
        body.set("contents", contents);

        if (system != null && !system.isBlank()) {
            var sys = mapper.createObjectNode();
            sys.putArray("parts").addObject().put("text", system);
            body.set("systemInstruction", sys);
        }

        var genConfig = mapper.createObjectNode();
        genConfig.put("temperature", temperature);
        genConfig.put("maxOutputTokens", 1024);
        body.set("generationConfig", genConfig);

        return body;
    }

    private LlmResponse parseResponse(String model, String raw, long latency) {
        try {
            JsonNode node = mapper.readTree(raw);
            StringBuilder sb = new StringBuilder();
            JsonNode candidates = node.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                for (JsonNode p : candidates.get(0).path("content").path("parts")) {
                    sb.append(p.path("text").asText(""));
                }
            }
            int inTok = node.path("usageMetadata").path("promptTokenCount").asInt(0);
            int outTok = node.path("usageMetadata").path("candidatesTokenCount").asInt(0);
            return new LlmResponse(sb.toString(), model, inTok, outTok, latency);
        } catch (Exception e) {
            log.warn("Failed parsing Gemini response: {}", e.getMessage());
            return new LlmResponse("[gemini-parse-error]", model, 0, 0, latency);
        }
    }

    private String extractTextChunk(String sseLine) {
        if (sseLine == null || sseLine.isBlank()) return "";
        try {
            // WebClient already strips the "data: " prefix when using SSE decoding, but be defensive.
            String trimmed = sseLine.startsWith("data:") ? sseLine.substring(5).trim() : sseLine.trim();
            if (trimmed.isEmpty() || trimmed.equals("[DONE]")) return "";
            JsonNode node = mapper.readTree(trimmed);
            StringBuilder sb = new StringBuilder();
            for (JsonNode cand : node.path("candidates")) {
                for (JsonNode p : cand.path("content").path("parts")) {
                    sb.append(p.path("text").asText(""));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // --- offline fallbacks so the app is demo-able without a real key ---

    private LlmResponse mockResponse(String model, String system, String user) {
        String text = "[mock-%s] I understand you said: %s. (Set AURA_GEMINI_API_KEY for real answers.)"
                .formatted(model, user.length() > 140 ? user.substring(0, 140) + "..." : user);
        return new LlmResponse(text, model + "/mock", user.length() / 4, text.length() / 4, 12);
    }

    private List<String> mockChunks(String system, String user) {
        return List.of(
                "I understand your request. ",
                "Based on the context I have, ",
                "here is my recommendation. ",
                "(This is a mock response — set AURA_GEMINI_API_KEY to get real output.)");
    }
}
