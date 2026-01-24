package com.aura.core.agent;

import com.aura.core.rag.RetrievalService;
import com.aura.core.rag.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Thin wrapper around the RAG retrieval service. Exists as a distinct "agent" so it shows
 * up as its own span in the trace viewer.
 */
@Component
public class RetrieverAgent {

    public static final String NAME = "retriever";

    private final RetrievalService retrieval;

    public RetrieverAgent(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    public AgentExecution<List<RetrievedChunk>> retrieve(String query) {
        long t0 = System.currentTimeMillis();
        List<RetrievedChunk> chunks = retrieval.retrieve(query);
        long latency = System.currentTimeMillis() - t0;
        return new AgentExecution<>(NAME, "pgvector-hnsw", 0, 0, latency, chunks);
    }
}
