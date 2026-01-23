package com.aura.core.rag;

import ai.djl.MalformedModelException;
import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import com.aura.core.config.AuraProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HuggingFace sentence-transformer embeddings (all-MiniLM-L6-v2, 384 dim) via DJL.
 * <p>
 * The model is downloaded to a local DJL cache on first use. Pure-Java inference (no
 * Python sidecar) keeps the deploy footprint to just the core JAR.
 * <p>
 * Falls back to a deterministic hash-based mock if the model cannot be loaded, so the
 * application still runs end-to-end in environments without internet egress (e.g. CI).
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final AuraProperties props;
    private final AtomicReference<ZooModel<String, float[]>> modelRef = new AtomicReference<>();
    private final AtomicReference<Predictor<String, float[]>> predictorRef = new AtomicReference<>();
    private volatile boolean usingMock = false;

    public EmbeddingService(AuraProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        try {
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.pytorch/" + props.embeddings().modelId())
                    .optEngine("PyTorch")
                    .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                    .build();
            ZooModel<String, float[]> model = criteria.loadModel();
            modelRef.set(model);
            predictorRef.set(model.newPredictor());
            log.info("Loaded embedding model {} (dim={})", props.embeddings().modelId(), props.embeddings().dimensions());
        } catch (IOException | ModelNotFoundException | MalformedModelException e) {
            log.warn("Could not load DJL embedding model ({}). Falling back to deterministic mock.",
                    e.getMessage());
            usingMock = true;
        }
    }

    public float[] embed(String text) {
        if (usingMock) return mockEmbed(text);
        try {
            float[] vec = predictorRef.get().predict(text);
            return l2Normalize(vec);
        } catch (TranslateException e) {
            log.warn("Embedding inference failed, falling back to mock for this call: {}", e.getMessage());
            return mockEmbed(text);
        }
    }

    public List<float[]> embedAll(List<String> texts) {
        if (usingMock) {
            List<float[]> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(mockEmbed(t));
            return out;
        }
        try {
            List<float[]> raw = predictorRef.get().batchPredict(texts);
            List<float[]> normed = new ArrayList<>(raw.size());
            for (float[] v : raw) normed.add(l2Normalize(v));
            return normed;
        } catch (TranslateException e) {
            log.warn("Batch embedding failed, falling back to per-text mock: {}", e.getMessage());
            List<float[]> out = new ArrayList<>(texts.size());
            for (String t : texts) out.add(mockEmbed(t));
            return out;
        }
    }

    public int dimensions() {
        return props.embeddings().dimensions();
    }

    public boolean isMock() {
        return usingMock;
    }

    @PreDestroy
    void close() {
        var p = predictorRef.get();
        if (p != null) p.close();
        var m = modelRef.get();
        if (m != null) m.close();
    }

    // --- helpers ---

    private static float[] l2Normalize(float[] v) {
        double sq = 0;
        for (float f : v) sq += f * f;
        double norm = Math.sqrt(sq);
        if (norm <= 1e-8) return v;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    /** Deterministic pseudo-embedding so the pipeline is testable without model weights. */
    private float[] mockEmbed(String text) {
        int dim = props.embeddings().dimensions();
        float[] v = new float[dim];
        long seed = 1469598103934665603L;
        for (int i = 0; i < text.length(); i++) {
            seed ^= text.charAt(i);
            seed *= 1099511628211L;
        }
        java.util.Random r = new java.util.Random(seed);
        for (int i = 0; i < dim; i++) v[i] = (float) (r.nextGaussian());
        return l2Normalize(v);
    }
}
