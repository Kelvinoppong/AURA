# AURA Architecture

This doc expands on the README with the pieces that are too detailed for a front page:
per-agent contracts, the state machine transitions, failure modes, and cost math.

## 1. Service topology

```
Browser
  │                                        +-- Postgres 16 + pgvector --+
  │ HTTPS + WSS                            |   users / conversations /  |
  ▼                                        |   messages / tickets /     |
+-------------+   HTTP + SSE   +--------+  |   knowledge_* / traces     |
| Go gateway  | ─────────────► | Java   | ─┘                            |
| :8081       |                | core   |                               |
| Gin + WS    | ◄───────────── | :8080  | ─► Gemini 2.0 (Pro / Flash)  |
| Redis       |    tokens      | WebFlux| ─► DJL embeddings (MiniLM)   |
+-------------+                +--------+                               |
       │                                                                |
       └────► Redis 7 (sessions, rate, conn-count) ──────────────────── ┘
```

Why split gateway and core? Two reasons:
1. **Streaming concurrency** — Go's goroutine model is a much better fit for holding
   many long-lived WebSocket connections than a Java thread-per-request stack.
2. **Defense in depth** — the gateway is the only thing the public can reach. The core
   can run with no auth on `/internal/*` because the network boundary is the auth boundary.

## 2. Turn lifecycle

```
1. Browser sends over WS:
     {"type":"chat","conversation_id":"...","message":"How do I rotate an API key?"}

2. Gateway validates JWT -> extracts user id -> calls core:
     POST /internal/chat/stream
     X-Aura-User: <uuid>
     {"conversationId":"...","message":"..."}

3. Core orchestrator:
     a. appendMessage(user)
     b. RouterAgent.classify() -> {category, priority, needs_retrieval}
     c. if needs_retrieval: RetrieverAgent.retrieve() -> top-k chunks
     d. DrafterAgent.stream() -> SSE event:token stream
     e. on complete: appendMessage(assistant) + QaAgent.review()
     f. if rejected OR priority==urgent: EscalationAgent.escalate() -> Ticket
     g. write agent_traces rows for every step

4. Gateway streams each token back over WS as {"type":"token","value":"..."}
5. Browser renders tokens into the current assistant message; final {"type":"done"}
   triggers a re-fetch of /api/chat/conversations/{id}/traces so the trace panel
   reflects the QA + escalation steps that happened after the stream ended.
```

## 3. Agent contracts

### Router
Input: raw user message. Output JSON:
```json
{
  "category": "billing|tech|account|general|small_talk",
  "priority": "low|normal|high|urgent",
  "needs_retrieval": true,
  "reason": "short justification"
}
```
Runs on FAST tier. Temperature 0.1. Strictly-parsed; malformed JSON falls back to
`needs_retrieval=true` so we err on the side of grounding.

### Retriever
Input: user message. Output: list of `RetrievedChunk { id, docId, docTitle, content, score }`.
`score = 1 - cosine_distance`. Filtered by `aura.rag.min-score` (default 0.25).
Backed by pgvector HNSW index (`m=16`, `ef_construction=64`).

### Drafter
Input: user message + retrieved chunks. Output: plain text reply (streams).
REASONING tier. Temperature 0.3. Prompt (see [drafter.txt](../core/src/main/resources/prompts/drafter.txt))
instructs it to treat chunks as ground truth and defer when uncertain.

### QA
Input: user message + chunks + draft. Output:
```json
{ "score": 0.87, "approved": true, "issues": [], "suggested_edit": "" }
```
FAST tier, temperature 0. The Orchestrator reads `approved` AND `score >= qa-min-score`
before shipping the draft as-is. If QA returns a non-empty `suggested_edit`, it's
preferred over the original draft when escalation also fires.

### Escalation
Deterministic (no LLM call). Creates a row in `tickets` with status `escalated` and
links it to the conversation. Fires when:
- QA `approved == false`, OR
- `priority == "urgent"`, OR
- QA `score < aura.orchestrator.escalation-threshold` (default 0.35)

## 4. LLM router economics

Indicative USD per 1M tokens, early 2026:

| model             | input  | output |
| ----------------- | -----: | -----: |
| gemini-2.0-flash  | $0.075 | $0.30  |
| gemini-2.0-pro    | $1.25  | $5.00  |

A typical turn costs, roughly:

| approach                 | calls                                                  | est. tokens  | est. cost |
| ------------------------ | ------------------------------------------------------ | -----------: | --------: |
| Reasoning-only (Pro x3)  | Router(Pro) + Drafter(Pro) + QA(Pro)                   |  ~4.5k / 1.2k | $0.011    |
| Hybrid (Flash/Pro/Flash) | Router(Flash) + Drafter(Pro) + QA(Flash)               |  ~4.5k / 1.2k | $0.0069   |

Delta is ≈ **$0.004 / query** before counting prompt compression (which further
shaves ~25% of Pro input tokens). Over 1000 queries that's $4 saved on LLM spend alone,
and the savings grow linearly with traffic.

Latency: Flash typically returns under 500ms for short inputs vs 1.2-2s for Pro, so
moving the Router + QA hops to Flash cuts per-turn wallclock by ~30-40%. The
`scripts/bench-router.sh` script reproduces this A/B on your own key.

## 5. Observability

Every LLM call + retrieval + escalation writes one row into `agent_traces`:

| column            | example                                    |
| ----------------- | ------------------------------------------ |
| agent_name        | `router` / `drafter` / `qa` / `escalate`   |
| model             | `gemini-2.0-flash` / `gemini-2.0-pro`      |
| prompt_tokens     | 812                                         |
| output_tokens     | 71                                          |
| latency_ms        | 430                                         |
| status            | `ok` / `rejected` / `skipped`              |
| payload (jsonb)   | agent-specific structured output           |

The UI trace panel reads `/api/chat/conversations/{id}/traces`. The "Telemetry" page
reads the live in-memory rolling aggregate from `/api/telemetry` — useful for grafana-style
dashboards or to burn down a per-org budget in real time.

## 6. Failure modes + fallbacks

| failure                                  | fallback                                                  |
| ---------------------------------------- | --------------------------------------------------------- |
| `AURA_GEMINI_API_KEY` not set            | `GeminiClient` returns canned mock text (UI still works)  |
| DJL can't download model weights         | Deterministic hash-based pseudo-embeddings (search works) |
| Gemini request times out                 | `[gemini-error] ...` text; trace row `status=error`       |
| pgvector HNSW returns nothing            | Drafter runs with empty context; prompt tells it to defer |
| Router returns malformed JSON            | Default `needs_retrieval=true, category=general`          |
| QA returns malformed JSON                | Default `approved=true score=0.7`                         |
| Gateway can't reach core                 | WS client receives `{"type":"error","error":"..."}`       |
| Redis unreachable                        | Rate limiter fails open; connection counter returns 0     |

## 7. What's intentionally NOT here

- Kubernetes / EKS manifests. Docker Compose is sufficient for the demo + EC2 deploy.
- Multi-tenant user accounts. One seeded demo user.
- Real secret management (Vault / KMS). Secrets live in `.env` for simplicity.
- Streaming embeddings. Seed + upload is synchronous/batched.
- Tool-calling agents (e.g., "check this ticket status in Zendesk"). The architecture
  supports it — LangChain4j `AiService` tools slot in — but it's out of scope.
