package com.aura.core.telemetry;

import com.aura.core.llm.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory rolling telemetry for the LLM router. The orchestrator persists per-turn
 * traces to Postgres separately; this class exposes an aggregate snapshot used by
 * {@code /api/telemetry} and the `make bench` command.
 * <p>
 * Cost per 1M tokens is approximate -- configurable so the bench script can re-price.
 */
@Component
public class TelemetryRecorder {

    private static final Logger log = LoggerFactory.getLogger(TelemetryRecorder.class);

    // Indicative USD prices (per 1M tokens) as of early 2026. Keep these pessimistic --
    // the resume claim is "$0.004/query savings" which survives small price changes.
    private static final double USD_PER_M_IN_FAST = 0.075;
    private static final double USD_PER_M_OUT_FAST = 0.30;
    private static final double USD_PER_M_IN_PRO = 1.25;
    private static final double USD_PER_M_OUT_PRO = 5.00;

    private final ConcurrentHashMap<String, ModelStats> stats = new ConcurrentHashMap<>();

    public void record(String agent, LlmResponse r) {
        ModelStats s = stats.computeIfAbsent(r.model(), k -> new ModelStats());
        s.calls.incrementAndGet();
        s.promptTokens.addAndGet(r.promptTokens());
        s.outputTokens.addAndGet(r.outputTokens());
        s.totalLatencyMs.addAndGet(r.latencyMs());
        log.debug("[{}] model={} in={} out={} latency={}ms", agent, r.model(), r.promptTokens(), r.outputTokens(), r.latencyMs());
    }

    public Snapshot snapshot() {
        var map = new java.util.LinkedHashMap<String, ModelSummary>();
        for (var e : stats.entrySet()) {
            ModelStats s = e.getValue();
            long calls = s.calls.get();
            long inTok = s.promptTokens.get();
            long outTok = s.outputTokens.get();
            long latency = s.totalLatencyMs.get();
            map.put(e.getKey(), new ModelSummary(
                    calls,
                    inTok,
                    outTok,
                    calls == 0 ? 0 : latency / calls,
                    estimateCost(e.getKey(), inTok, outTok)));
        }
        return new Snapshot(map);
    }

    public void reset() {
        stats.clear();
    }

    private static double estimateCost(String model, long inTok, long outTok) {
        boolean pro = model.contains("pro");
        double inRate = pro ? USD_PER_M_IN_PRO : USD_PER_M_IN_FAST;
        double outRate = pro ? USD_PER_M_OUT_PRO : USD_PER_M_OUT_FAST;
        return (inTok / 1_000_000.0) * inRate + (outTok / 1_000_000.0) * outRate;
    }

    // --- value types ---

    private static final class ModelStats {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong outputTokens = new AtomicLong();
        final AtomicLong totalLatencyMs = new AtomicLong();
    }

    public record ModelSummary(long calls, long promptTokens, long outputTokens, long avgLatencyMs, double usd) {}

    public record Snapshot(java.util.Map<String, ModelSummary> perModel) {}
}
