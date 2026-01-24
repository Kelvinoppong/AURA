package com.aura.core.agent;

public record Classification(
        String category,
        String priority,
        boolean needsRetrieval,
        String reason
) {
    public static Classification smallTalkDefault() {
        return new Classification("small_talk", "low", false, "fallback-small-talk");
    }
}
