package com.aura.core.knowledge;

import com.aura.core.rag.EmbeddingService;
import com.aura.core.rag.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Splits raw documents into overlapping character windows, embeds them in batches,
 * and writes the vectors into Postgres. Tuned for throughput on the 50k-chunk seed.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private static final int CHUNK_SIZE = 700;
    private static final int CHUNK_OVERLAP = 120;
    private static final int BATCH_SIZE = 64;

    private final EmbeddingService embedder;
    private final VectorStore store;

    public IngestionService(EmbeddingService embedder, VectorStore store) {
        this.embedder = embedder;
        this.store = store;
    }

    public UUID ingest(String title, String source, String text) {
        UUID docId = store.upsertDoc(title, source, null);
        List<String> chunks = chunk(text);
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<String> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            List<float[]> vecs = embedder.embedAll(batch);
            store.insertChunks(docId, batch, vecs);
        }
        log.info("Ingested '{}' -> {} chunks", title, chunks.size());
        return docId;
    }

    public void ingestPreChunked(String title, String source, List<String> chunks) {
        UUID docId = store.upsertDoc(title, source, null);
        for (int i = 0; i < chunks.size(); i += BATCH_SIZE) {
            List<String> batch = chunks.subList(i, Math.min(i + BATCH_SIZE, chunks.size()));
            List<float[]> vecs = embedder.embedAll(batch);
            store.insertChunks(docId, batch, vecs);
        }
    }

    static List<String> chunk(String text) {
        List<String> out = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            int end = Math.min(i + CHUNK_SIZE, n);
            out.add(text.substring(i, end).trim());
            if (end == n) break;
            i = end - CHUNK_OVERLAP;
        }
        return out;
    }
}
