package com.aura.core.agent;

import java.util.List;

public record QaVerdict(
        double score,
        boolean approved,
        List<String> issues,
        String suggestedEdit
) {}
