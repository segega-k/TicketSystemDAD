# ticket-system-dad

Java 21 / Spring Boot 3 ticketing and event booking backend with a Vite/React frontend. The canonical HTTP API is rooted at `/api/v1`.

## Features

- JWT auth with register/login/refresh/logout and refresh-token revocation.
- Events API with search/cursor pagination, details, organizer creation, and generated seat maps.
- Seat holds for 1-6 adjacent seats, Redis TTL, Redis Lua atomic hold, rate limiting, and test fallback.
- Booking confirmation with `Idempotency-Key`, DB uniqueness, history/get/cancel, refund records, and ticket PDF endpoint.
- Organizer dashboard/analytics, audit events, outbox table, Redis publish-ready scheduled publisher.
- WebSocket/STOMP `/ws` seat updates on `/topic/events/{eventId}/seats`.
- RFC7807 ProblemDetail with `trace_id`, OpenAPI at `/swagger-ui.html`, actuator health/prometheus metrics.

## Run with Docker Compose

Requirements: Docker with Compose v2. The backend image builds from this repository with Maven; a prebuilt `target/*.jar` is not required.

```bash
make jwt-keys              # optional but recommended; creates infra/jwt/*.pem
make compose-up-detached   # or: docker compose up -d --build
```

Services exposed for local development:

- Frontend: http://localhost:3000
- Backend API v1: http://localhost:8080/api/v1
- Swagger UI: http://localhost:8080/swagger-ui.html
- OpenAPI JSON: http://localhost:8080/v3/api-docs
- Health: http://localhost:8080/actuator/health

Stop containers without deleting database data:

```bash
make compose-down
```

Delete persisted Compose data and re-run Flyway seed data:

```bash
make compose-down-volumes
make compose-up-detached
```

Optional Prometheus is available behind the observability profile because `docs/observability/prometheus.yml` exists:

```bash
docker compose --profile observability up -d --build
# Prometheus: http://localhost:9090
```

## Local development

Backend requires Java 21. Frontend requires Node.js 20+.

```bash
./mvnw -B -ntp clean verify
cd frontend
npm ci
npm run lint
npm run test -- --passWithNoTests
npm run build
```

Backend-only local run with local Postgres/Redis defaults:

```bash
./mvnw spring-boot:run
```

Useful Make targets:

```bash
make validate        # backend verify + frontend lint/test/build + compose config
make backend-run
make frontend-build
make compose-up-detached
make compose-logs
make smoke
```

## Environment variables

Compose defaults are development-only and can be overridden with shell variables or a local `.env` file. See `.env.example` for a copyable template.

| Variable                                                    | Default                                                        | Notes                                                                                                                                       |
| ----------------------------------------------------------- | -------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `POSTGRES_DB`                                               | `ticket_system_dad`                                            | Database name created by the Postgres container.                                                                                            |
| `POSTGRES_USER` / `POSTGRES_PASSWORD`                       | `ticket_system_dad` / `ticket_system_dad`                      | Development database credentials.                                                                                                           |
| `POSTGRES_PORT`                                             | `5432`                                                         | Host port mapped to container Postgres.                                                                                                     |
| `SPRING_DATASOURCE_URL`                                     | `jdbc:postgresql://postgres:5432/ticket_system_dad`            | Backend JDBC URL inside Compose. For local non-Docker runs the application default is `jdbc:postgresql://localhost:5432/ticket_system_dad`. |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `ticket_system_dad` / `ticket_system_dad`                      | Backend database credentials.                                                                                                               |
| `REDIS_HOST`                                                | `redis`                                                        | Backend Redis host inside Compose (`localhost` for local non-Docker runs).                                                                  |
| `REDIS_PORT`                                                | `6379`                                                         | Host port mapped to Redis and also the application default for local runs.                                                                  |
| `REDIS_CONTAINER_PORT`                                      | `6379`                                                         | Redis port used by the backend container.                                                                                                   |
| `JWT_RSA_PRIVATE_KEY_PATH` / `JWT_RSA_PUBLIC_KEY_PATH`      | `/run/secrets/jwt_private.pem` / `/run/secrets/jwt_public.pem` | PEM files mounted from `./infra/jwt`. Run `make jwt-keys` to create them.                                                                   |
| `JWT_RSA_PRIVATE_KEY` / `JWT_RSA_PUBLIC_KEY`                | empty                                                          | Inline PEM alternatives used by Spring if path variables are not set.                                                                       |
| `APP_REDIS_FAIL_CLOSED`                                     | `true`                                                         | If true, Redis write-path failures fail closed instead of using fallback behavior.                                                          |
| `BACKEND_PORT` / `BACKEND_CONTAINER_PORT`                   | `8080` / `8080`                                                | Backend host/container ports.                                                                                                               |
| `FRONTEND_PORT`                                             | `3000`                                                         | Host port for frontend nginx.                                                                                                               |
| `VITE_API_BASE_URL`                                         | `/api/v1`                                                      | Frontend API base at build/dev time.                                                                                                        |

If no RSA PEM variables resolve to readable keys, the backend generates an ephemeral RS256 keypair at startup; this is acceptable only for short local runs because tokens are invalidated on restart.

## Demo credentials

Flyway seed users use password `password123`:

- Organizer: `organizer@example.com`
- Customer: `customer@example.com`

## Validation and smoke checks

```bash
make validate
make smoke BACKEND_URL=http://localhost:8080 FRONTEND_URL=http://localhost:3000
```

Manual smoke:

```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/api/v1/events
curl -fsSI http://localhost:3000
```

CI runs backend `./mvnw clean verify` and frontend `npm ci`, lint, tests, and build.

## API v1 contract

The stable HTTP surface is documented in [`docs/api-v1.md`](docs/api-v1.md). Use `/api/v1` for all application endpoints. A few `/api/*` aliases remain for older local clients; do not use those aliases in new docs, tests, or examples. Actuator, Swagger UI, and `/v3/api-docs` are operational endpoints outside API v1.

## Docker caveats

- Compose is for local/staging validation, not production TLS termination, secret rotation, or horizontal-scaling proof.
- This Compose file runs one backend service directly on port 8080; it does not include the SPEC's nginx gateway or two backend replicas.
- Backend Docker build skips tests; CI and `make validate` run tests separately.
- Compose exposes Postgres and Redis on localhost for developer convenience.
- Named volumes persist Postgres/Redis data across `docker compose down`; use `down -v` to reset seed data.
- Frontend is built as static files served by nginx and is not a hot-reload dev server.
