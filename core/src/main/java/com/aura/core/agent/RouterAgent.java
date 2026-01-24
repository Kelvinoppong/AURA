package com.aura.core.agent;

import com.aura.core.llm.LlmResponse;
import com.aura.core.llm.LlmRouter;
import com.aura.core.llm.ModelTier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Classifies the incoming user message (category + priority + whether retrieval is needed).
 * Always runs on the FAST tier (Gemini Flash): classification is cheap.
 */
@Component
public class RouterAgent {

    public static final String NAME = "router";
    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);

    private final LlmRouter llm;
    private final PromptLoader prompts;
    private final ObjectMapper mapper = new ObjectMapper();

    public RouterAgent(LlmRouter llm, PromptLoader prompts) {
        this.llm = llm;
        this.prompts = prompts;
    }

    public AgentExecution<Classification> classify(String userMessage) {
        String system = prompts.load("router");
        LlmResponse res = llm.generate(ModelTier.FAST, NAME, system, userMessage, 0.1);
        Classification c = parse(res.text());
        return new AgentExecution<>(NAME, res.model(), res.promptTokens(), res.outputTokens(), res.latencyMs(), c);
    }

    private Classification parse(String raw) {
        try {
            String cleaned = stripCodeFences(raw);
            JsonNode node = mapper.readTree(cleaned);
            return new Classification(
                    node.path("category").asText("general"),
                    node.path("priority").asText("normal"),
                    node.path("needs_retrieval").asBoolean(true),
                    node.path("reason").asText(""));
        } catch (Exception e) {
            log.warn("Router JSON parse failed, falling back to needs_retrieval=true. Raw: {}", raw);
            return new Classification("general", "normal", true, "parse-fallback");
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
