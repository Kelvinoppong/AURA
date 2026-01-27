#!/usr/bin/env bash
# Seeds ~50,000 synthetic customer-support knowledge chunks into AURA.
#
# Each synthetic "doc" carries ~500 chunks across 6 categories (billing, tech, account,
# product, integrations, policy). We POST batches of chunks to the core's
# /api/knowledge/ingest-chunks endpoint so we amortise the DJL embedding warm-up cost.

set -euo pipefail

CORE_URL="${CORE_URL:-http://localhost:8080}"
TOTAL_CHUNKS="${TOTAL_CHUNKS:-50000}"
CHUNKS_PER_DOC="${CHUNKS_PER_DOC:-500}"

CATEGORIES=(
  "billing:invoice,payment,subscription,refund,chargeback,proration,plan upgrade,downgrade,tax,vat"
  "tech:api,webhook,latency,timeout,retry,rate limit,sdk,pagination,auth,rotation"
  "account:sso,mfa,password,session,workspace,invitation,role,permission,email,domain"
  "product:feature,roadmap,beta,release,notification,export,import,analytics,dashboard,widget"
  "integrations:slack,salesforce,zendesk,jira,github,segment,hubspot,intercom,zapier,webhook"
  "policy:gdpr,ccpa,hipaa,soc2,retention,dpa,subprocessor,security,dsar,pii"
)

command -v curl >/dev/null 2>&1 || { echo "need curl"; exit 1; }
command -v jq   >/dev/null 2>&1 || { echo "need jq"; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "need python3"; exit 1; }

echo "==> Seeding $TOTAL_CHUNKS chunks to $CORE_URL (chunks/doc=$CHUNKS_PER_DOC)"
echo "==> This will take a few minutes on first run while embeddings warm up."

DOCS=$(( TOTAL_CHUNKS / CHUNKS_PER_DOC ))
INDEX=0

for (( d=0; d<DOCS; d++ )); do
  CAT_LINE=${CATEGORIES[$(( d % ${#CATEGORIES[@]} ))]}
  CAT_NAME="${CAT_LINE%%:*}"
  KEYWORDS="${CAT_LINE#*:}"

  PAYLOAD=$(python3 - "$CAT_NAME" "$KEYWORDS" "$CHUNKS_PER_DOC" "$d" <<'PY'
import json, random, sys
cat, keywords_raw, n, d = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
rng = random.Random(f"aura-{cat}-{d}")
keywords = keywords_raw.split(",")
templates = [
    "When a customer asks about {kw}, explain that {verb} is handled by our {system} and resolves within {sla}.",
    "Our {kw} policy: {rule}. Exceptions require approval from {owner}.",
    "Troubleshooting {kw}: first check {check_a}, then confirm {check_b}, then escalate if {escalate_when}.",
    "{kw} FAQ: customers on the {plan} plan can {permission}; others must contact support.",
    "Internal runbook for {kw}: step 1 — verify {verify}; step 2 — run {command}; step 3 — document in {system}.",
]
filler = {
    "verb": ["refunds", "retries", "rollbacks", "upgrades", "exports"],
    "system": ["Stripe", "the internal admin", "our ops console", "the billing service"],
    "sla": ["24h", "1 business day", "72h", "48h"],
    "rule": ["charges are prorated", "data is retained 30 days", "tokens expire in 15 min",
             "tiers are evaluated monthly", "failures auto-retry 3x"],
    "owner": ["the on-call engineer", "billing ops", "the security team", "the account manager"],
    "check_a": ["their plan tier", "the API key rotation log", "the webhook delivery dashboard"],
    "check_b": ["recent org changes", "rate limit metrics", "SSO redirect URIs"],
    "escalate_when": ["status is 5xx", "the user is on the Enterprise plan", "data loss is suspected"],
    "plan": ["Free", "Pro", "Business", "Enterprise"],
    "permission": ["self-serve exports", "invite up to 50 users", "enable custom SSO"],
    "verify": ["the request signature", "the target environment", "the idempotency key"],
    "command": ["the reconcile job", "the retry worker", "the export pipeline"],
}
chunks = []
for i in range(n):
    kw = rng.choice(keywords)
    t = rng.choice(templates)
    f = {k: rng.choice(v) for k, v in filler.items()}
    chunks.append(t.format(kw=kw, **f))
print(json.dumps({
    "title": f"{cat.capitalize()} Knowledge Pack #{d+1}",
    "source": "seed",
    "chunks": chunks,
}))
PY
)

  HTTP=$(curl -sS -o /tmp/aura-seed.out -w "%{http_code}" \
    -X POST "$CORE_URL/api/knowledge/ingest-chunks" \
    -H "Content-Type: application/json" \
    --data-binary @<(echo "$PAYLOAD"))
  if [[ "$HTTP" != "200" ]]; then
    echo "Ingest failed (HTTP $HTTP): $(cat /tmp/aura-seed.out)"
    exit 1
  fi
  INDEX=$(( INDEX + CHUNKS_PER_DOC ))
  printf "  [%3d/%3d] +%d chunks (total %d)\n" "$((d+1))" "$DOCS" "$CHUNKS_PER_DOC" "$INDEX"
done

echo "==> Done. Querying stats..."
curl -sS "$CORE_URL/api/knowledge/stats" | jq .
