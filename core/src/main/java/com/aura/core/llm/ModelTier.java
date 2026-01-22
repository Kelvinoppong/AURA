package com.aura.core.llm;

/**
 * Cost/latency tier that each agent declares. The {@link LlmRouter} maps a tier to the
 * concrete Gemini model (fast = Flash, reasoning = Pro). This is the substrate behind the
 * "hybrid LLM routing layer" claim on the resume.
 */
public enum ModelTier {
    /** Classification, short QA checks, query expansion. Cheap + fast. */
    FAST,
    /** Multi-step reasoning, grounded drafting, summarisation. */
    REASONING
}
