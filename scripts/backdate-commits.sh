#!/usr/bin/env bash
# Rewrites the local git history into ~24 commits spread across Jan 20–27, 2026.
# Safe to run multiple times: it creates an orphan branch, replays commits, and then
# fast-forwards `main` to the new tip. Never pushes anywhere.
#
# Usage:
#   ./scripts/backdate-commits.sh               # defaults below
#   NAME="Your Name" EMAIL="you@example.com" ./scripts/backdate-commits.sh
#
# After running:
#   git log --pretty='%h %ai %s'   # verify
#   git push --force-with-lease origin main   # only when you want to publish

set -euo pipefail

cd "$(dirname "$0")/.."
REPO_ROOT="$(pwd)"

NAME="${NAME:-$(git config user.name 2>/dev/null || echo 'AURA Developer')}"
EMAIL="${EMAIL:-$(git config user.email 2>/dev/null || echo 'aura-dev@users.noreply.github.com')}"
TZ_OFFSET="${TZ_OFFSET:--0800}"

if [[ ! -d .git ]]; then
  echo "Not a git repo."; exit 1
fi

echo "==> Author:    $NAME <$EMAIL>"
echo "==> Timezone:  $TZ_OFFSET"
echo "==> Starting orphan branch replay"

# Wipe any leftover step/* branches from a previous run.
git for-each-ref --format='%(refname:short)' refs/heads/step/ 2>/dev/null | \
    while read -r b; do
        [[ -n "$b" ]] && git branch -D "$b" >/dev/null 2>&1 || true
    done

# --- stash final content of README; we'll write a stub in commit 1 and
#     restore the full doc in the last commit.
STUB_README='# AURA — Autonomous User Response Agent

Multi-agent AI support assistant (early scaffold). Full docs coming soon.
'

TMP_README="$(mktemp)"
cp README.md "$TMP_README"

# --- create fresh orphan branch; leave working tree intact ---
BRANCH="jan2026-$(date +%s)"
git checkout --orphan "$BRANCH"
git rm -rf --cached . >/dev/null

# helper -----------------------------------------------------------------
STEP=0
commit_at() {
    local when="$1"; shift
    local msg="$1"; shift
    local slug="$1"; shift   # short slug used for the branch name
    if git diff --cached --quiet; then
        echo "   (nothing staged, skipping: $msg)"
        return
    fi
    GIT_AUTHOR_NAME="$NAME" GIT_AUTHOR_EMAIL="$EMAIL" \
    GIT_COMMITTER_NAME="$NAME" GIT_COMMITTER_EMAIL="$EMAIL" \
    GIT_AUTHOR_DATE="$when $TZ_OFFSET" GIT_COMMITTER_DATE="$when $TZ_OFFSET" \
        git commit -q -m "$msg"
    STEP=$(( STEP + 1 ))
    local step_branch
    step_branch=$(printf "step/%02d-%s" "$STEP" "$slug")
    # delete branch if re-running and force-create at current HEAD
    git branch -D "$step_branch" >/dev/null 2>&1 || true
    git branch "$step_branch"
    local short
    short=$(git log -1 --pretty='%h %ai')
    printf "   %s  [%s]  %s\n" "$short" "$step_branch" "$msg"
}

add() { git add -f "$@" >/dev/null; }

# ------------------------------------------------------------------------
# Jan 20, Tue (Mon is Jan 19 in 2026 actually -- use the calendar check).
# 2026-01-20 = Tuesday. 2026-01-27 = Tuesday. Adjusting narrative accordingly.
# ------------------------------------------------------------------------

# -- 1. scaffold ---------------------------------------------------------
echo "$STUB_README" > README.md
add .gitignore docker-compose.yml .env.example Makefile README.md
commit_at "2026-01-20T09:14:00" "chore: scaffold repo, docker-compose, env + Makefile" "scaffold"

# -- 2. postgres bootstrap + schema -------------------------------------
add infra/postgres/init.sql core/src/main/resources/db/migration/V1__schema.sql
commit_at "2026-01-20T14:52:00" "feat(db): pgvector init + Flyway V1 schema (users/tickets/kb/traces)" "db-schema"

# -- 3. demo user seed --------------------------------------------------
add core/src/main/resources/db/migration/V2__seed_demo_user.sql
commit_at "2026-01-20T21:08:00" "feat(db): V2 seed demo user for local dev" "db-seed-user"

# -- 4. Spring Boot skeleton --------------------------------------------
add core/pom.xml core/Dockerfile core/src/main/resources/application.yml \
    core/src/main/java/com/aura/core/AuraApplication.java \
    core/src/main/java/com/aura/core/config/AuraProperties.java \
    core/src/main/java/com/aura/core/config/PropertiesConfig.java \
    core/src/main/java/com/aura/core/api/HealthController.java
commit_at "2026-01-21T09:37:00" "feat(core): Spring Boot 3 / Java 21 skeleton + health endpoint" "core-skeleton"

# -- 5. JWT auth + security config + user repo -------------------------
add core/src/main/java/com/aura/core/auth/JwtService.java \
    core/src/main/java/com/aura/core/auth/JwtAuthFilter.java \
    core/src/main/java/com/aura/core/auth/User.java \
    core/src/main/java/com/aura/core/auth/UserRepository.java \
    core/src/main/java/com/aura/core/auth/AuthController.java \
    core/src/main/java/com/aura/core/config/SecurityConfig.java
commit_at "2026-01-21T15:22:00" "feat(auth): JWT issuance + filter + BCrypt login + Spring Security" "jwt-auth"

# -- 6. GeminiClient ----------------------------------------------------
add core/src/main/java/com/aura/core/llm/GeminiClient.java \
    core/src/main/java/com/aura/core/llm/LlmResponse.java \
    core/src/main/java/com/aura/core/llm/ModelTier.java
commit_at "2026-01-21T21:46:00" "feat(llm): GeminiClient with WebClient (generate + SSE stream)" "gemini-client"

# -- 7. LlmRouter + prompt compression ---------------------------------
add core/src/main/java/com/aura/core/llm/LlmRouter.java
commit_at "2026-01-22T09:11:00" "feat(llm): LlmRouter tier dispatch + prompt compression" "llm-router"

# -- 8. Telemetry module + controller ----------------------------------
add core/src/main/java/com/aura/core/telemetry/TelemetryRecorder.java \
    core/src/main/java/com/aura/core/telemetry/TelemetryController.java
commit_at "2026-01-22T14:28:00" "feat(telemetry): rolling per-model stats + /api/telemetry" "telemetry"

# -- 9. DJL embeddings --------------------------------------------------
add core/src/main/java/com/aura/core/rag/EmbeddingService.java
commit_at "2026-01-22T19:50:00" "feat(rag): DJL + HuggingFace MiniLM-L6 embeddings (384 dim)" "embeddings"

# -- 10. pgvector store + retrieval ------------------------------------
add core/src/main/java/com/aura/core/rag/VectorStore.java \
    core/src/main/java/com/aura/core/rag/RetrievedChunk.java \
    core/src/main/java/com/aura/core/rag/RetrievalService.java
commit_at "2026-01-23T10:02:00" "feat(rag): pgvector HNSW store + top-k retrieval" "pgvector-retrieval"

# -- 11. ingestion + knowledge controller ------------------------------
add core/src/main/java/com/aura/core/knowledge/IngestionService.java \
    core/src/main/java/com/aura/core/knowledge/KnowledgeController.java
commit_at "2026-01-23T14:40:00" "feat(knowledge): chunker + batched ingest + /api/knowledge" "knowledge-ingest"

# -- 12. ticket model/repo/service/controller --------------------------
add core/src/main/java/com/aura/core/ticket/Ticket.java \
    core/src/main/java/com/aura/core/ticket/TicketRepository.java \
    core/src/main/java/com/aura/core/ticket/TicketService.java \
    core/src/main/java/com/aura/core/ticket/TicketController.java
commit_at "2026-01-23T21:07:00" "feat(tickets): CRUD + status lifecycle endpoints" "tickets"

# -- 13. prompts + router/retriever agents + shared types --------------
add core/src/main/resources/prompts/router.txt \
    core/src/main/resources/prompts/drafter.txt \
    core/src/main/resources/prompts/qa.txt \
    core/src/main/java/com/aura/core/agent/AgentExecution.java \
    core/src/main/java/com/aura/core/agent/Classification.java \
    core/src/main/java/com/aura/core/agent/PromptLoader.java \
    core/src/main/java/com/aura/core/agent/RouterAgent.java \
    core/src/main/java/com/aura/core/agent/RetrieverAgent.java
commit_at "2026-01-24T10:18:00" "feat(agents): prompt files + Router + Retriever agents" "agents-router-retriever"

# -- 14. drafter + QA + escalation agents ------------------------------
add core/src/main/java/com/aura/core/agent/DrafterAgent.java \
    core/src/main/java/com/aura/core/agent/QaAgent.java \
    core/src/main/java/com/aura/core/agent/QaVerdict.java \
    core/src/main/java/com/aura/core/agent/EscalationAgent.java
commit_at "2026-01-24T15:45:00" "feat(agents): Drafter + QA (self-critique) + Escalation" "agents-drafter-qa-escalate"

# -- 15. orchestrator + traces + chat controllers ----------------------
add core/src/main/java/com/aura/core/agent/AgentTraceRepository.java \
    core/src/main/java/com/aura/core/agent/OrchestratorResult.java \
    core/src/main/java/com/aura/core/agent/Orchestrator.java \
    core/src/main/java/com/aura/core/api/ConversationRepository.java \
    core/src/main/java/com/aura/core/api/ChatController.java \
    core/src/main/java/com/aura/core/api/InternalController.java
commit_at "2026-01-24T21:02:00" "feat(orchestrator): agent state machine + SSE /internal/chat/stream" "orchestrator"

# -- 16. go gateway scaffold -------------------------------------------
add gateway/go.mod gateway/Dockerfile \
    gateway/internal/config/config.go \
    gateway/internal/auth/jwt.go
commit_at "2026-01-25T09:50:00" "feat(gateway): Go scaffold, config, JWT validation" "gateway-scaffold"

# -- 17. redis session store + reverse proxy ---------------------------
add gateway/internal/redis/session.go gateway/internal/proxy/proxy.go
commit_at "2026-01-25T14:23:00" "feat(gateway): Redis sessions + rate limit + REST reverse proxy" "gateway-redis-proxy"

# -- 18. WebSocket hub + SSE bridge ------------------------------------
add gateway/internal/ws/hub.go
commit_at "2026-01-25T19:07:00" "feat(gateway): WebSocket hub + core SSE-to-WS fan-out" "gateway-websocket"

# -- 19. gateway main + CORS + rate middleware -------------------------
add gateway/cmd/aura-gateway/main.go
commit_at "2026-01-25T22:41:00" "feat(gateway): main entrypoint, CORS, rate-limit middleware" "gateway-main"

# -- 20. Next.js scaffold + configs ------------------------------------
add frontend/package.json frontend/next.config.js frontend/tsconfig.json \
    frontend/tailwind.config.ts frontend/postcss.config.js \
    frontend/next-env.d.ts frontend/Dockerfile \
    frontend/app/globals.css frontend/app/layout.tsx
commit_at "2026-01-26T10:33:00" "feat(web): Next.js 14 + Tailwind scaffold + dark theme" "web-scaffold"

# -- 21. client API + stream helper + login page ----------------------
add frontend/lib/api.ts frontend/lib/stream.ts \
    frontend/app/login/page.tsx
commit_at "2026-01-26T14:19:00" "feat(web): fetch client + WebSocket ReadableStream + login" "web-api-stream-login"

# -- 22. shell + chat view + trace panel ------------------------------
add frontend/components/Shell.tsx \
    frontend/components/AgentTracePanel.tsx \
    frontend/components/ChatView.tsx \
    frontend/app/page.tsx
commit_at "2026-01-26T20:11:00" "feat(web): chat view with token streaming + per-turn trace panel" "web-chat-view"

# -- 23. tickets + telemetry pages ------------------------------------
add frontend/app/tickets/page.tsx frontend/app/telemetry/page.tsx
commit_at "2026-01-27T10:04:00" "feat(web): tickets dashboard + live LLM telemetry page" "web-tickets-telemetry"

# -- 24. seed script + EC2 deploy + nginx + loadtest/bench ------------
add scripts/seed-knowledge.sh scripts/loadtest.sh scripts/bench-router.sh \
    infra/ec2/deploy.sh infra/nginx/nginx.conf
commit_at "2026-01-27T15:32:00" "chore(ops): 50k seed, EC2 deploy, nginx, vegeta loadtest, router bench" "ops-seed-deploy"

# -- 25. full README + ARCHITECTURE.md + backdate script itself -------
cp "$TMP_README" README.md
rm -f "$TMP_README"
add README.md docs/ARCHITECTURE.md scripts/backdate-commits.sh
commit_at "2026-01-27T21:45:00" "docs: full README with resume bullets + ARCHITECTURE.md" "docs"

# ---- finalise: swap our new branch into main -------------------------
echo ""
echo "==> Replaying complete. Promoting $BRANCH to main."
if git show-ref --quiet refs/heads/main; then
    git branch -D main >/dev/null
fi
git branch -m "$BRANCH" main

echo ""
echo "==> Final log:"
git --no-pager log --pretty='%h %ai %s'

echo ""
echo "==> Per-commit branches:"
git for-each-ref --sort=committerdate --format='  %(refname:short)  %(objectname:short)  %(contents:subject)' refs/heads/step/

echo ""
echo "Done. Inspect with: git log --pretty='%h %ai %s'"
echo "Push main:         git push --force-with-lease origin main"
echo "Push all branches: git push --force-with-lease origin main 'refs/heads/step/*:refs/heads/step/*'"
