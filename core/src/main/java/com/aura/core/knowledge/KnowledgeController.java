package com.aura.core.knowledge;

import com.aura.core.rag.RetrievalService;
import com.aura.core.rag.RetrievedChunk;
import com.aura.core.rag.VectorStore;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final IngestionService ingestion;
    private final RetrievalService retrieval;
    private final VectorStore store;

    public KnowledgeController(IngestionService ingestion, RetrievalService retrieval, VectorStore store) {
        this.ingestion = ingestion;
        this.retrieval = retrieval;
        this.store = store;
    }

    public record IngestRequest(@NotBlank String title, String source, @NotBlank String text) {}

    public record IngestResponse(UUID docId, String title) {}

    public record IngestPreChunkedRequest(@NotBlank String title, String source, List<String> chunks) {}

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@RequestBody IngestRequest req) {
        UUID id = ingestion.ingest(req.title(), req.source() == null ? "upload" : req.source(), req.text());
        return ResponseEntity.ok(new IngestResponse(id, req.title()));
    }

    /** Used by the 50k seed script to bulk-insert already-chunked synthetic docs. */
    @PostMapping("/ingest-chunks")
    public ResponseEntity<IngestResponse> ingestChunks(@RequestBody IngestPreChunkedRequest req) {
        UUID id = java.util.UUID.randomUUID();
        ingestion.ingestPreChunked(req.title(), req.source() == null ? "seed" : req.source(), req.chunks());
        return ResponseEntity.ok(new IngestResponse(id, req.title()));
    }

    @GetMapping("/search")
    public List<RetrievedChunk> search(@RequestParam("q") String q,
                                       @RequestParam(value = "k", required = false, defaultValue = "6") int k) {
        return retrieval.retrieve(q, k);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("chunks", store.countChunks());
    }
}
