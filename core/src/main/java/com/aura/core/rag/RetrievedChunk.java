package com.aura.core.rag;

import java.util.UUID;

public record RetrievedChunk(
        UUID chunkId,
        UUID docId,
        String docTitle,
        String content,
        double score
) {}
