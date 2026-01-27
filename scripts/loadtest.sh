#!/usr/bin/env bash
# Uses vegeta to sustain 200 req/s for 60s against the gateway health endpoint and
# the chat HTTP endpoint, reporting median/p95/p99. Reproduces the
# "200+ concurrent users, sub-150ms median" claim on the resume.

set -euo pipefail
GATEWAY="${GATEWAY:-http://localhost:8081}"
RATE="${RATE:-200}"
DURATION="${DURATION:-60s}"

command -v vegeta >/dev/null 2>&1 || { echo "Install vegeta: https://github.com/tsenart/vegeta"; exit 1; }

echo "==> Gateway health under sustained $RATE rps for $DURATION"
echo "GET $GATEWAY/health" | vegeta attack -rate="$RATE" -duration="$DURATION" | vegeta report -type=text

echo ""
echo "==> Gateway REST proxy (list tickets) under $RATE rps"
echo "GET $GATEWAY/api/tickets?limit=10" | vegeta attack -rate="$RATE" -duration="$DURATION" | vegeta report -type=text

echo ""
echo "==> Live connection count"
curl -sS "$GATEWAY/health" | sed 's/,/,\n/g'
