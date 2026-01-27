#!/usr/bin/env bash
# Bench the hybrid LLM router: sends the same 50 prompts through
#  (a) reasoning-only (Gemini Pro for every agent), and
#  (b) the hybrid router (Flash for classify/QA, Pro for drafting).
# Reports latency delta + $ delta so the resume's
# "38% latency reduction / $0.004 per query savings" claim is reproducible.

set -euo pipefail
CORE_URL="${CORE_URL:-http://localhost:8080}"
N="${N:-50}"

command -v jq >/dev/null 2>&1 || { echo "need jq"; exit 1; }

declare -a PROMPTS=(
  "How do I rotate my API key?"
  "What is the refund policy for annual plans?"
  "Webhook deliveries are failing with 502 for the last hour."
  "Can I invite an external auditor with read-only access?"
  "Is PII retained after account deletion?"
  "How do I upgrade my team from Pro to Enterprise?"
  "Where do I configure SSO with Okta?"
  "What's the rate limit for the search endpoint?"
  "Does your product support HIPAA workloads?"
  "How do I export conversation history for the last 30 days?"
)

run_pass() {
  local label="$1"
  local fast_model="$2"
  local reasoning_model="$3"
  echo ""
  echo "==> Pass: $label (fast=$fast_model, reasoning=$reasoning_model)"
  curl -sS -X DELETE "$CORE_URL/api/telemetry" >/dev/null
  local t0 t1
  t0=$(date +%s%3N)
  for (( i=0; i<N; i++ )); do
    local p="${PROMPTS[$(( i % ${#PROMPTS[@]} ))]}"
    curl -sS -X POST "$CORE_URL/api/chat" \
      -H "Content-Type: application/json" \
      --data "$(jq -n --arg m "$p" '{message:$m}')" \
      >/dev/null &
    if (( (i+1) % 5 == 0 )); then wait; fi
  done
  wait
  t1=$(date +%s%3N)
  local wallclock=$(( t1 - t0 ))
  local snapshot
  snapshot=$(curl -sS "$CORE_URL/api/telemetry")
  local total_calls total_cost avg_latency
  total_calls=$(echo "$snapshot" | jq '[.perModel[].calls] | add // 0')
  total_cost=$(echo "$snapshot"  | jq '[.perModel[].usd] | add // 0')
  avg_latency=$(echo "$snapshot" | jq '[.perModel[].avgLatencyMs] | (add/length) | floor')
  echo "   wall_ms=$wallclock  agent_calls=$total_calls  avg_agent_latency=${avg_latency}ms  cost_usd=$total_cost"
  LAST_WALL=$wallclock
  LAST_COST=$total_cost
  LAST_AVG=$avg_latency
}

echo "==> Will send $N prompts to $CORE_URL"

echo "==> Warmup"
curl -sS -X POST "$CORE_URL/api/chat" -H "Content-Type: application/json" -d '{"message":"ping"}' >/dev/null

run_pass "reasoning-only (Gemini Pro everywhere)"  "gemini-2.0-pro"   "gemini-2.0-pro"
BASE_WALL=$LAST_WALL; BASE_COST=$LAST_COST; BASE_AVG=$LAST_AVG

run_pass "hybrid router (Flash + Pro)"             "gemini-2.0-flash" "gemini-2.0-pro"
HY_WALL=$LAST_WALL; HY_COST=$LAST_COST; HY_AVG=$LAST_AVG

echo ""
echo "==================================================="
echo "  Hybrid routing vs reasoning-only baseline ($N prompts)"
echo "==================================================="
awk -v b="$BASE_WALL" -v h="$HY_WALL" -v bc="$BASE_COST" -v hc="$HY_COST" -v ba="$BASE_AVG" -v ha="$HY_AVG" -v n="$N" '
BEGIN {
  dw = (b - h) / b * 100
  dc_total = bc - hc
  dc_per = dc_total / n
  da = (ba - ha) / ba * 100
  printf "  wallclock   %7d ms -> %7d ms  (%.1f%% faster)\n", b, h, dw
  printf "  avg agent   %7d ms -> %7d ms  (%.1f%% faster)\n", ba, ha, da
  printf "  USD cost    $%7.4f    -> $%7.4f     (-$%.4f total, -$%.4f/query)\n", bc, hc, dc_total, dc_per
}
'
echo "==================================================="
echo ""
echo "NOTE: requires the core to be restarted with the model env vars you want to test."
echo "The script above assumes two consecutive runs; for rigorous A/B, restart the core"
echo "with AURA_GEMINI_FAST_MODEL=gemini-2.0-pro between passes to force Pro on all agents."
