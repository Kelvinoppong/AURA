package com.aura.core.api;

import com.aura.core.agent.Orchestrator;
import com.aura.core.agent.OrchestratorResult;
import com.aura.core.auth.UserRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Gateway-facing endpoints. The Go gateway validates the user's JWT and then calls these
 * passing the user's id in the X-Aura-User header. Security on {@code /internal/**} is
 * open; protect this by only reaching the core from inside the docker network.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final Orchestrator orchestrator;
    private final ConversationRepository conversations;
    private final UserRepository users;

    public InternalController(Orchestrator orchestrator, ConversationRepository conversations, UserRepository users) {
        this.orchestrator = orchestrator;
        this.conversations = conversations;
        this.users = users;
    }

    public record ChatRequest(UUID conversationId, @NotBlank String message) {}

    @PostMapping("/chat")
    public OrchestratorResult chat(@RequestHeader(value = "X-Aura-User", required = false) String userId,
                                   @RequestBody ChatRequest req) {
        UUID u = resolveUser(userId);
        UUID conv = req.conversationId() != null ? req.conversationId()
                : conversations.create(u, null).id();
        return orchestrator.handle(conv, req.message());
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream(
            @RequestHeader(value = "X-Aura-User", required = false) String userId,
            @RequestBody ChatRequest req) {
        UUID u = resolveUser(userId);
        UUID conv = req.conversationId() != null ? req.conversationId()
                : conversations.create(u, null).id();
        Flux<ServerSentEvent<Map<String, Object>>> tokens = orchestrator.stream(conv, req.message())
                .map(tok -> ServerSentEvent.<Map<String, Object>>builder()
                        .event("token")
                        .data(Map.of("type", "token", "value", tok))
                        .build());
        Flux<ServerSentEvent<Map<String, Object>>> terminator = Flux.just(
                ServerSentEvent.<Map<String, Object>>builder()
                        .event("done")
                        .data(Map.of("type", "done", "conversationId", conv.toString()))
                        .build());
        Flux<ServerSentEvent<Map<String, Object>>> keepalive = Flux.interval(Duration.ofSeconds(15))
                .map(i -> ServerSentEvent.<Map<String, Object>>builder().comment("keepalive").build());
        return tokens.concatWith(terminator).mergeWith(keepalive.takeUntilOther(tokens.then()));
    }

    private UUID resolveUser(String header) {
        if (header != null && !header.isBlank()) {
            try {
                return UUID.fromString(header);
            } catch (Exception ignored) { /* fall through */ }
        }
        return users.findByEmail("demo@aura.ai")
                .map(u -> u.id())
                .orElseThrow(() -> new IllegalStateException("No demo user seeded"));
    }
}
