package com.aura.core.agent;

import com.aura.core.api.ConversationRepository;
import com.aura.core.config.AuraProperties;
import com.aura.core.llm.LlmRouter;
import com.aura.core.llm.ModelTier;
import com.aura.core.rag.RetrievedChunk;
import com.aura.core.ticket.Ticket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Explicit state machine over the agent graph. This is AURA's "LangGraph-equivalent":
 * each agent is a node, edges are conditional on previous outputs, and every transition
 * is recorded to {@code agent_traces} for the trace viewer.
 *
 * <pre>
 *   Router --needs_retrieval--> Retriever --> Drafter --> QA --approved--> DONE
 *      \                                             \--rejected--> Escalate --> DONE
 *       \--small_talk--> Drafter (no context) --> QA --> DONE
 * </pre>
 */
@Service
public class Orchestrator {

    private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

    private final RouterAgent router;
    private final RetrieverAgent retriever;
    private final DrafterAgent drafter;
    private final QaAgent qa;
    private final EscalationAgent escalation;
    private final ConversationRepository conversations;
    private final AgentTraceRepository traces;
    private final LlmRouter llm;
    private final AuraProperties props;

    public Orchestrator(RouterAgent router, RetrieverAgent retriever, DrafterAgent drafter,
                        QaAgent qa, EscalationAgent escalation, ConversationRepository conversations,
                        AgentTraceRepository traces, LlmRouter llm, AuraProperties props) {
        this.router = router;
        this.retriever = retriever;
        this.drafter = drafter;
        this.qa = qa;
        this.escalation = escalation;
        this.conversations = conversations;
        this.traces = traces;
        this.llm = llm;
        this.props = props;
    }

    /** One turn. Blocking version for non-streaming endpoints + tests. */
    public OrchestratorResult handle(UUID conversationId, String userMessage) {
        long t0 = System.currentTimeMillis();
        var traceSteps = new ArrayList<OrchestratorResult.TraceStep>();

        // 0. persist user message
        var userMsg = conversations.appendMessage(conversationId, "user", userMessage);

        // 1. ROUTER
        var routed = router.classify(userMessage);
        Classification c = routed.output();
        traces.record(conversationId, userMsg.id(), routed, "ok", c);
        traceSteps.add(step(routed, "ok"));

        // 2. RETRIEVER (conditional)
        List<RetrievedChunk> context = List.of();
        if (c.needsRetrieval()) {
            var retrieved = retriever.retrieve(userMessage);
            context = retrieved.output();
            traces.record(conversationId, userMsg.id(), retrieved, "ok",
                    context.stream().map(RetrievedChunk::docTitle).toList());
            traceSteps.add(step(retrieved, "ok"));
        } else {
            traceSteps.add(new OrchestratorResult.TraceStep(
                    RetrieverAgent.NAME, "n/a", 0, 0, 0, "skipped"));
        }

        // 3. DRAFTER
        var drafted = drafter.draft(userMessage, context);
        String draft = drafted.output();
        traces.record(conversationId, userMsg.id(), drafted, "ok", null);
        traceSteps.add(step(drafted, "ok"));

        // 4. QA
        var reviewed = qa.review(userMessage, context, draft);
        QaVerdict verdict = reviewed.output();
        traces.record(conversationId, userMsg.id(), reviewed,
                verdict.approved() ? "ok" : "rejected", verdict);
        traceSteps.add(step(reviewed, verdict.approved() ? "ok" : "rejected"));

        // 5. ESCALATION (conditional)
        Ticket escalated = null;
        String finalReply;
        if (!verdict.approved() || "urgent".equals(c.priority())
                || verdict.score() < props.orchestrator().escalationThreshold()) {
            var esc = escalation.escalate(conversationId, userMessage, c,
                    verdict.approved() ? "urgent_priority" : "qa_rejected");
            escalated = esc.output();
            traces.record(conversationId, userMsg.id(), esc, "ok", escalated.id());
            traceSteps.add(step(esc, "ok"));
            finalReply = (verdict.suggestedEdit() != null && !verdict.suggestedEdit().isBlank())
                    ? verdict.suggestedEdit()
                    : draft + "\n\n(We've also opened ticket #" + escalated.id().toString().substring(0, 8)
                        + " so a human agent can follow up.)";
        } else {
            finalReply = draft;
        }

        // 6. persist assistant reply
        var assistantMsg = conversations.appendMessage(conversationId, "assistant", finalReply);

        long total = System.currentTimeMillis() - t0;
        return new OrchestratorResult(
                conversationId, assistantMsg.id(), finalReply, c, context, verdict, escalated, total, traceSteps);
    }

    /**
     * Streaming version. Runs Router+Retriever+QA as blocking pre/post steps and streams
     * only the Drafter tokens to the client. This keeps the critical path as "route ->
     * retrieve -> stream draft -> QA -> (maybe escalate)".
     */
    public Flux<String> stream(UUID conversationId, String userMessage) {
        var userMsg = conversations.appendMessage(conversationId, "user", userMessage);
        var routed = router.classify(userMessage);
        traces.record(conversationId, userMsg.id(), routed, "ok", routed.output());

        List<RetrievedChunk> context = List.of();
        if (routed.output().needsRetrieval()) {
            var retrieved = retriever.retrieve(userMessage);
            context = retrieved.output();
            traces.record(conversationId, userMsg.id(), retrieved, "ok",
                    context.stream().map(RetrievedChunk::docTitle).toList());
        }

        StringBuilder acc = new StringBuilder();
        final List<RetrievedChunk> ctx = context;
        return drafter.stream(userMessage, ctx)
                .doOnNext(acc::append)
                .doOnComplete(() -> {
                    // Persist final assistant message + run QA asynchronously for the trace viewer.
                    try {
                        String full = acc.toString();
                        conversations.appendMessage(conversationId, "assistant", full);
                        var reviewed = qa.review(userMessage, ctx, full);
                        traces.record(conversationId, userMsg.id(), reviewed,
                                reviewed.output().approved() ? "ok" : "rejected", reviewed.output());
                        if (!reviewed.output().approved()
                                || "urgent".equals(routed.output().priority())) {
                            var esc = escalation.escalate(conversationId, userMessage, routed.output(),
                                    reviewed.output().approved() ? "urgent_priority" : "qa_rejected");
                            traces.record(conversationId, userMsg.id(), esc, "ok", esc.output().id());
                        }
                    } catch (Exception e) {
                        log.warn("Post-stream QA/escalate failed: {}", e.getMessage());
                    }
                });
    }

    private static <T> OrchestratorResult.TraceStep step(AgentExecution<T> e, String status) {
        return new OrchestratorResult.TraceStep(
                e.agentName(), e.model(), e.promptTokens(), e.outputTokens(), e.latencyMs(), status);
    }

    public String llmModelFor(ModelTier tier) {
        return llm.modelFor(tier);
    }
}
