package com.aura.core.api;

import com.aura.core.agent.AgentTraceRepository;
import com.aura.core.agent.Orchestrator;
import com.aura.core.agent.OrchestratorResult;
import com.aura.core.auth.UserRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final Orchestrator orchestrator;
    private final ConversationRepository conversations;
    private final AgentTraceRepository traces;
    private final UserRepository users;

    public ChatController(Orchestrator orchestrator, ConversationRepository conversations,
                          AgentTraceRepository traces, UserRepository users) {
        this.orchestrator = orchestrator;
        this.conversations = conversations;
        this.traces = traces;
        this.users = users;
    }

    public record CreateConvRequest(String title) {}
    public record ChatRequest(UUID conversationId, @NotBlank String message) {}

    @PostMapping("/conversations")
    public ConversationRepository.Conversation create(@RequestBody(required = false) CreateConvRequest req) {
        UUID userId = currentUserId();
        return conversations.create(userId, req == null ? null : req.title());
    }

    @GetMapping("/conversations")
    public List<ConversationRepository.Conversation> list(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return conversations.listForUser(currentUserId(), limit);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<ConversationRepository.Message> messages(@PathVariable("id") UUID id) {
        return conversations.messages(id);
    }

    @GetMapping("/conversations/{id}/traces")
    public List<AgentTraceRepository.Row> traces(@PathVariable("id") UUID id,
                                                  @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        return traces.listByConversation(id, limit);
    }

    @PostMapping
    public OrchestratorResult chat(@RequestBody ChatRequest req) {
        UUID conv = req.conversationId() != null ? req.conversationId()
                : conversations.create(currentUserId(), null).id();
        return orchestrator.handle(conv, req.message());
    }

    /**
     * SSE stream consumed by the Go gateway and proxied to the browser over WebSocket.
     * Each event body is a JSON line: {"type":"token","value":"..."} or {"type":"done"}.
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream(@RequestBody ChatRequest req) {
        UUID conv = req.conversationId() != null ? req.conversationId()
                : conversations.create(currentUserId(), null).id();
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

    private UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            // Fall back to the seeded demo user so unauthenticated local runs still work.
            return users.findByEmail("demo@aura.ai")
                    .map(u -> u.id())
                    .orElseThrow(() -> new RuntimeException("No authenticated user and no demo seed"));
        }
        try {
            return UUID.fromString(auth.getPrincipal().toString());
        } catch (Exception e) {
            return users.findByEmail("demo@aura.ai").map(u -> u.id()).orElseThrow();
        }
    }
}
