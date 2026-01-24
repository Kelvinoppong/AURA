package com.aura.core.agent;

import com.aura.core.llm.LlmResponse;
import com.aura.core.llm.LlmRouter;
import com.aura.core.llm.ModelTier;
import com.aura.core.rag.RetrievedChunk;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Drafts the reply given the retrieved context. Runs on the REASONING tier (Gemini Pro).
 */
@Component
public class DrafterAgent {

    public static final String NAME = "drafter";

    private final LlmRouter llm;
    private final PromptLoader prompts;

    public DrafterAgent(LlmRouter llm, PromptLoader prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    public AgentExecution<String> draft(String userMessage, List<RetrievedChunk> context) {
        String system = prompts.load("drafter");
        String user = renderUserPrompt(userMessage, context);
        LlmResponse res = llm.generate(ModelTier.REASONING, NAME, system, user, 0.3);
        return new AgentExecution<>(NAME, res.model(), res.promptTokens(), res.outputTokens(), res.latencyMs(), res.text());
    }

    public Flux<String> stream(String userMessage, List<RetrievedChunk> context) {
        String system = prompts.load("drafter");
        String user = renderUserPrompt(userMessage, context);
        return llm.stream(ModelTier.REASONING, NAME, system, user, 0.3);
    }

    private static String renderUserPrompt(String userMessage, List<RetrievedChunk> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER MESSAGE:\n").append(userMessage).append("\n\n");
        if (context != null && !context.isEmpty()) {
            sb.append("KNOWLEDGE SNIPPETS (ranked, most relevant first):\n");
            int i = 1;
            for (RetrievedChunk c : context) {
                sb.append("[").append(i++).append("] (").append(c.docTitle()).append(", score=")
                        .append(String.format("%.2f", c.score())).append(")\n")
                        .append(c.content()).append("\n\n");
            }
        } else {
            sb.append("KNOWLEDGE SNIPPETS: (none relevant above threshold)\n");
        }
        sb.append("Write the customer-facing reply now.");
        return sb.toString();
    }
}
