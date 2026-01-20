.PHONY: help up down logs ps rebuild seed bench loadtest fmt

help:
	@echo "AURA - Autonomous User Response Agent"
	@echo ""
	@echo "Targets:"
	@echo "  up         Start full stack (docker compose)"
	@echo "  down       Stop stack and remove containers"
	@echo "  logs       Tail all service logs"
	@echo "  ps         List running services"
	@echo "  rebuild    Rebuild all images and restart"
	@echo "  seed       Seed 50k synthetic knowledge-base chunks"
	@echo "  bench      Run LLM router A/B benchmark"
	@echo "  loadtest   Run WebSocket load test (vegeta)"

up:
	docker compose up -d --build

down:
	docker compose down

logs:
	docker compose logs -f --tail=200

ps:
	docker compose ps

rebuild:
	docker compose build --no-cache && docker compose up -d

seed:
	./scripts/seed-knowledge.sh

bench:
	./scripts/bench-router.sh

loadtest:
	./scripts/loadtest.sh

fmt:
	cd core && ./mvnw spotless:apply || true
	cd gateway && go fmt ./...
	cd frontend && npm run format || true
