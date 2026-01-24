package com.aura.core.agent;

import com.aura.core.llm.LlmResponse;
import com.aura.core.llm.LlmRouter;
import com.aura.core.llm.ModelTier;
import com.aura.core.rag.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Critiques the drafted reply before it's sent. Runs on FAST tier -- evaluating short text
 * doesn't need a reasoning model, and the whole reason the router exists is to not waste
 * Pro-tier tokens on this kind of work.
 */
@Component
public class QaAgent {

    public static final String NAME = "qa";
    private static final Logger log = LoggerFactory.getLogger(QaAgent.class);

    private final LlmRouter llm;
    private final PromptLoader prompts;
    private final ObjectMapper mapper = new ObjectMapper();

    public QaAgent(LlmRouter llm, PromptLoader prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    public AgentExecution<QaVerdict> review(String userMessage, List<RetrievedChunk> context, String draft) {
        String system = prompts.load("qa");
        String user = renderUserPrompt(userMessage, context, draft);
        LlmResponse res = llm.generate(ModelTier.FAST, NAME, system, user, 0.0);
        QaVerdict v = parse(res.text());
        return new AgentExecution<>(NAME, res.model(), res.promptTokens(), res.outputTokens(), res.latencyMs(), v);
    }

    private static String renderUserPrompt(String userMessage, List<RetrievedChunk> context, String draft) {
        StringBuilder sb = new StringBuilder();
        sb.append("USER MESSAGE:\n").append(userMessage).append("\n\n");
        sb.append("RETRIEVED CONTEXT:\n");
        if (context != null) {
            for (RetrievedChunk c : context) {
                sb.append("- ").append(c.content()).append("\n");
            }
        }
        sb.append("\nDRAFTED REPLY:\n").append(draft).append("\n");
        return sb.toString();
    }

    private QaVerdict parse(String raw) {
        try {
            String cleaned = stripCodeFences(raw);
            JsonNode node = mapper.readTree(cleaned);
            List<String> issues = new ArrayList<>();
            for (JsonNode i : node.path("issues")) issues.add(i.asText());
            return new QaVerdict(
                    node.path("score").asDouble(0.5),
                    node.path("approved").asBoolean(false),
                    issues,
                    node.path("suggested_edit").asText(""));
        } catch (Exception e) {
            log.warn("QA JSON parse failed, defaulting to approved=true score=0.7. Raw: {}", raw);
            return new QaVerdict(0.7, true, List.of("qa-parse-fallback"), "");
        }
    }

    private static String stripCodeFences(String s) {
        if (s == null) return "{}";
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNl = t.indexOf('\n');
            if (firstNl > 0) t = t.substring(firstNl + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3).trim();
        }
        return t;
    }
}
