package com.aura.core.rag;

import com.aura.core.config.AuraProperties;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Query-time RAG service. Embeds a user query, runs HNSW ANN search in Postgres,
 * filters results below the similarity floor and returns the remaining chunks.
 */
@Service
public class RetrievalService {

    private final EmbeddingService embedder;
    private final VectorStore store;
    private final AuraProperties props;

    public RetrievalService(EmbeddingService embedder, VectorStore store, AuraProperties props) {
        this.embedder = embedder;
        this.store = store;
        this.props = props;
    }

    public List<RetrievedChunk> retrieve(String query) {
        return retrieve(query, props.rag().topK());
    }

    public List<RetrievedChunk> retrieve(String query, int topK) {
        float[] q = embedder.embed(query);
        List<RetrievedChunk> raw = store.search(q, topK);
        double floor = props.rag().minScore();
        return raw.stream().filter(c -> c.score() >= floor).toList();
    }
}
