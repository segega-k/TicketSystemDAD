SHELL := /bin/bash
.DEFAULT_GOAL := help

COMPOSE ?= docker compose
BACKEND_URL ?= http://localhost:8080
FRONTEND_URL ?= http://localhost:3000

.PHONY: help test build run backend-verify backend-build backend-run frontend-install frontend-lint frontend-test frontend-build frontend-validate validate jwt-keys certs load-test-seat-map load-test-hold-burst compose-config compose-up compose-up-detached compose-down compose-down-volumes compose-logs compose-ps smoke

help: ## Show available targets
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_.-]+:.*##/ {printf "%-24s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

test: backend-verify ## Alias for backend verification

build: backend-build ## Alias for backend package build

run: backend-run ## Alias for backend local run

backend-verify: ## Run backend unit/integration verification
	./mvnw -B -ntp clean verify

backend-build: ## Build backend jar without tests
	./mvnw -B -ntp -DskipTests package

backend-run: ## Run backend with local Postgres/Redis env
	./mvnw spring-boot:run

frontend-install: ## Install frontend dependencies from lockfile
	cd frontend && npm ci

frontend-lint: ## Run frontend ESLint
	cd frontend && npm run lint

frontend-test: ## Run frontend tests; allow empty test suites in skeletons
	cd frontend && npm run test -- --passWithNoTests

frontend-build: ## Type-check and build frontend assets
	cd frontend && npm run build

frontend-validate: frontend-install frontend-lint frontend-test frontend-build ## Run all frontend checks

validate: backend-verify frontend-validate compose-config ## Run backend, frontend, and compose validations

jwt-keys: ## Generate local RS256 JWT PEM keypair for Docker Compose
	mkdir -p infra/jwt
	test -f infra/jwt/jwt_private.pem || \
		openssl genpkey -algorithm RSA -out infra/jwt/jwt_private.pem -pkeyopt rsa_keygen_bits:2048
	test -f infra/jwt/jwt_public.pem || \
		openssl rsa -in infra/jwt/jwt_private.pem -pubout -out infra/jwt/jwt_public.pem

certs: ## Generate self-signed TLS cert for nginx (dev only)
	mkdir -p infra/nginx/certs
	test -f infra/nginx/certs/server.crt || openssl req -x509 -nodes -newkey rsa:2048 \
		-keyout infra/nginx/certs/server.key \
		-out    infra/nginx/certs/server.crt \
		-days 365 -subj "/CN=tickets.local"

load-test-seat-map: ## Run k6 against GET /events/{id}/seats and record the results
	mkdir -p load-tests/results
	docker run --rm --network ticketing_internal \
		-v $(PWD)/load-tests:/scripts \
		grafana/k6 run /scripts/seat_map_load.js \
		--out json=/scripts/results/seat_map_$(shell date +%Y%m%d_%H%M%S).json

load-test-hold-burst: ## Burst the hold endpoint to validate the token-bucket rate limiter
	docker run --rm --network ticketing_internal \
		-v $(PWD)/load-tests:/scripts \
		grafana/k6 run /scripts/hold_burst.js

compose-config: ## Render/validate Docker Compose YAML
	@if command -v docker >/dev/null 2>&1; then \
		$(COMPOSE) config; \
	else \
		echo "docker not found; skipped compose config"; \
	fi

compose-up: jwt-keys certs ## Build and run the full stack in the foreground
	$(COMPOSE) up --build

compose-up-detached: jwt-keys certs ## Build and run the full stack in the background
	$(COMPOSE) up -d --build

compose-down: ## Stop containers but keep named volumes
	$(COMPOSE) down

compose-down-volumes: ## Stop containers and delete named volumes
	$(COMPOSE) down -v

compose-logs: ## Tail compose logs
	$(COMPOSE) logs -f --tail=200

compose-ps: ## Show compose service status
	$(COMPOSE) ps

smoke: ## Check backend health/events and frontend HTTP response
	curl -fsS "$(BACKEND_URL)/actuator/health" && echo
	curl -fsS "$(BACKEND_URL)/api/v1/events" && echo
	curl -fsSI "$(FRONTEND_URL)" | head -n 1
