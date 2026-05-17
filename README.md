# Ticket System DAD

Flash-sale-safe ticketing and event-booking platform. Java 21 + Spring Boot 3.3
backend, Vite + React 18 frontend, deployed as **two backend replicas behind an
nginx gateway** with a full OpenTelemetry-based observability stack
(Prometheus + Loki + Tempo + Grafana). Built for the *Database Application and
Design* course (Spring 2026, Inha University in Tashkent).

- **Deployed:** http://35.234.70.163/ — Swagger UI:
  http://35.234.70.163/swagger-ui/index.html
- **Grafana:** http://35.234.70.163:3001/ — **Prometheus:** http://35.234.70.163:9090/
- **Report:** `report/report.pdf` — **Spec:** [`SPEC.md`](SPEC.md) — **API:** [`docs/api-v1.md`](docs/api-v1.md)
- **Diagrams:** ER, architecture, sequence, BPMN (Mermaid sources in [`docs/`](docs/), PNG renders in [`docs/img/`](docs/img/))

## Features (mapped to course requirements R1–R13)

- **Auth (R4):** JWT RS256, 30-min access + 7-day refresh tokens with rotation
  and revocation. BCrypt(cost=12).
- **Events (R4):** fuzzy search via Postgres `pg_trgm`, cursor pagination,
  organizer event creation, generated seat maps with `(tier, row, seat)` layout.
- **Seat holds (R5 + R11):** 1–6 adjacent seats per request, Redis TTL of 600 s,
  **two from-scratch Lua scripts** — `acquire_hold.lua` (atomic multi-key + per-event
  cap) and `token_bucket.lua` (lazy-refill rate limiter). In-memory fallback
  with `APP_REDIS_FAIL_CLOSED` toggle.
- **Bookings (R4):** mandatory `Idempotency-Key`, transactional commit with DB
  uniqueness, history / detail / cancel + recorded refund, QR-coded PDF ticket.
- **Realtime (R7):** STOMP-over-WebSocket on `/ws`, topic
  `/topic/events/{eventId}/seats`, cross-replica fan-out via Redis Pub/Sub
  channel `ws.broadcast` consumed by `RedisStompBridge`.
- **Pipeline (R10):** Spring Batch `dailySalesJob` cron `0 0 2 * * *` UTC
  → `analytics.event_daily_sales`; transactional outbox poller (200 ms tick,
  `FOR UPDATE SKIP LOCKED`) drives Redis Pub/Sub. BPMN diagrams in `docs/bpmn/`.
- **Gateway and replicas (R8 + R9):** nginx 1.27 with `least_conn` for HTTP and
  `ip_hash` for `/ws`, TLS termination at `:443`, two `backend-N` Spring Boot
  instances orchestrated by one `docker-compose.yml`.
- **Observability (R12):** OpenTelemetry Java agent → otel-collector → Loki
  (logs), Tempo (traces), Prometheus (scrapes `/actuator/prometheus`). Four
  pre-provisioned Grafana dashboards (`api-overview`, `bookings-funnel`,
  `ratelimit`, `outbox`).
- **Errors (R13):** RFC 7807 `ProblemDetail` everywhere with `trace_id`
  propagated from W3C `traceparent`. Stable error `code` strings for the client.
- **Docs (R13):** OpenAPI 3 / Swagger UI at `/swagger-ui/index.html`,
  CHANGELOG, this README, and `docs/api-v1.md`.

## Quick start (local Docker Compose)

Requirements: Docker with Compose v2, GNU make, ~3 GB free RAM. Java/Node are
**not** required on the host — the backend image builds from this repo with
Maven, and the frontend is bundled by the frontend Dockerfile.

```bash
make jwt-keys              # generates infra/jwt/*.pem (RSA 2048)
make certs                 # generates a self-signed TLS cert for nginx
make compose-up-detached   # docker compose up -d --build
```

This brings up all 11 services on the `internal` bridge network:
**postgres, redis, backend-1, backend-2, frontend, nginx, otel-collector,
prometheus, loki, tempo, grafana.**

### Local endpoints

| Surface          | URL                                                |
| ---------------- | -------------------------------------------------- |
| Frontend SPA     | http://localhost/                                  |
| Backend API v1   | http://localhost/api/v1/                           |
| Swagger UI       | http://localhost/swagger-ui/index.html             |
| OpenAPI JSON     | http://localhost/v3/api-docs                       |
| Actuator health  | http://localhost/actuator/health                   |
| WebSocket STOMP  | ws://localhost/ws                                  |
| Grafana          | http://localhost:3001/  (admin / admin)            |
| Prometheus       | http://localhost:9090/                             |

Configure `HTTP_PORT` / `HTTPS_PORT` / `GRAFANA_PORT` / `PROMETHEUS_PORT` in
`.env` to remap host ports if you need to.

### Stop, restart, reset

```bash
make compose-down            # stop, keep named volumes
make compose-down-volumes    # stop AND drop volumes (re-runs Flyway seed)
make compose-logs            # tail nginx + backend-1/2 logs
make smoke BACKEND_URL=http://localhost FRONTEND_URL=http://localhost
```

## Demo credentials

Flyway seeds two accounts with password `password123`:

- Organizer: `organizer@example.com`
- Customer:  `customer@example.com`

A default administrator is provisioned on backend startup
(`config/AdminBootstrap.java`). The credentials are constant and not
configurable via env vars:

- Admin:    `admin@example.com`
- Password: `Admin12345!`

The admin can manage organizer / analyst accounts at `/admin/users` in the web
UI and hard-delete events (which cascades to confirmed bookings as full
refunds). Organizers can delete only their own events.

## Local development (without Docker)

If you want to run backend or frontend natively for fast iteration, start the
data plane in Compose and point the app at `localhost`:

```bash
docker compose up -d postgres redis
./mvnw spring-boot:run                      # backend on :8080
cd frontend && npm ci && npm run dev        # frontend dev server on :5173
```

Backend requires **Java 21** (we test on Eclipse Temurin 21). Frontend requires
**Node 20+**. Local datasource defaults to `jdbc:postgresql://localhost:5432/ticket_system_dad`
and Redis to `localhost:6379`.

Run the full validation chain (matches CI) with:

```bash
make validate    # mvn verify + npm lint/test/build + docker compose config
```

Run the k6 load profiles (Spec §11 quantitative threshold):

```bash
make load-test-seat-map BACKEND_URL=http://localhost   # 100 VUs × 60 s, p95 < 400 ms
make load-test-hold-burst BACKEND_URL=http://localhost # rate-limit verification
```

## Environment variables

Compose defaults live in `.env.example`. All variables below also work as plain
shell environment variables.

| Variable                                                    | Default                                                        | Notes |
| ----------------------------------------------------------- | -------------------------------------------------------------- | ----- |
| `POSTGRES_DB`                                               | `ticket_system_dad`                                            | Database name. |
| `POSTGRES_USER` / `POSTGRES_PASSWORD`                       | `ticket_system_dad` / `ticket_system_dad`                      | DB credentials. |
| `POSTGRES_PORT`                                             | `5432`                                                         | Postgres is **internal-only** in the published Compose; not host-mapped. |
| `SPRING_DATASOURCE_URL`                                     | `jdbc:postgresql://postgres:5432/ticket_system_dad`            | JDBC URL inside Compose; for native runs the default is `jdbc:postgresql://localhost:5432/ticket_system_dad`. |
| `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | `ticket_system_dad` / `ticket_system_dad`                      | Backend DB credentials. |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_CONTAINER_PORT`        | `redis` / `6379` / `6379`                                      | Redis is also internal-only in Compose. |
| `APP_REDIS_FAIL_CLOSED`                                     | `true`                                                         | `true` → write path returns 503 on Redis outage; `false` → in-memory fallback (dev only). |
| `JWT_RSA_PRIVATE_KEY_PATH` / `JWT_RSA_PUBLIC_KEY_PATH`      | `/run/secrets/jwt_private.pem` / `/run/secrets/jwt_public.pem` | PEM files mounted from `./infra/jwt`. Run `make jwt-keys` once. |
| `JWT_RSA_PRIVATE_KEY` / `JWT_RSA_PUBLIC_KEY`                | empty                                                          | Inline PEM alternative if path variables are not set. |
| `HTTP_PORT` / `HTTPS_PORT`                                  | `80` / `443`                                                   | Host ports for the **nginx gateway** (the only public ingress). |
| `PROMETHEUS_PORT` / `GRAFANA_PORT`                          | `9090` / `3001`                                                | Observability host ports. |
| `VITE_API_BASE_URL`                                         | `/api/v1`                                                      | Frontend API base at build/dev time. |
| `GRAFANA_ADMIN_USER` / `GRAFANA_ADMIN_PASSWORD`             | `admin` / `admin`                                              | Grafana login. Change for any non-local deploy. |

If neither RSA PEM variable resolves to readable keys, the backend generates an
ephemeral 2048-bit RS256 keypair at startup and **logs a WARN** — fine for a
local hack session, never for production because every restart invalidates all
outstanding access tokens.

## API v1 contract

The canonical HTTP surface is documented in [`docs/api-v1.md`](docs/api-v1.md);
Swagger UI is served from `/swagger-ui/index.html` on every backend. Use
`/api/v1` for new code. A few legacy `/api/*` aliases survive for older
clients — do not use them in new docs, tests, or examples.

Error responses follow **RFC 7807 ProblemDetail** with stable `code` strings
defined in `config/ProblemAdvice.java`. Every response carries `trace_id` /
`X-Trace-Id`; `429 Too Many Requests` additionally includes a `Retry-After`
header and a `retry_after_seconds` property.

## Architecture in one paragraph

The hot path for a flash-sale-safe booking goes: customer `POST /seats/hold` →
nginx → one of the two `backend-N` replicas → token-bucket rate-limit (Lua) →
atomic multi-seat acquire (Lua) → Postgres outbox row in the same transaction.
The 200 ms outbox poller publishes the event to Redis channel `ws.broadcast`,
and every replica's `RedisStompBridge` re-publishes to its local STOMP
subscribers — so every browser sees `SEAT_HELD` regardless of which replica it
is connected to. On payment, `POST /bookings` re-verifies the hold inside the
same Postgres transaction that promotes seats to `BOOKED`. See
[`docs/diagrams/sequence-hold-pay.mmd`](docs/diagrams/sequence-hold-pay.mmd)
for the full message flow and the report PDF for the design discussion.

## Repository layout

```
.
├── src/main/java/uz/inha/tickets/       Backend (45 .java files)
│   ├── config/          SecurityConfig, ProblemAdvice, WebSocketConfig, AdminBootstrap
│   ├── web/             6 @RestControllers
│   ├── service/         AuthService, JwtService, HoldService, BookingService, OutboxPublisher, …
│   ├── domain/          10 JPA @Entities
│   ├── repo/            9 Spring Data JPA repositories
│   ├── batch/           DailySalesScheduler + Job (Spring Batch)
│   ├── ws/              RedisStompBridge
│   ├── tickets/         TicketPdfService (OpenPDF + ZXing)
│   └── observability/   LogMdcFilter (W3C traceparent → MDC)
├── src/main/resources/
│   ├── application.yml
│   ├── db/migration/    V1..V4 Flyway SQL
│   └── redis/           acquire_hold.lua, token_bucket.lua (R11)
├── frontend/            React 18 + Vite 5 + TypeScript SPA
├── infra/
│   ├── nginx/           gateway config + cert dir
│   ├── otel/            otel-collector pipelines
│   ├── prometheus/      scrape config (backend-1 + backend-2)
│   ├── loki/  tempo/    log + trace storage
│   ├── grafana/         datasources + 4 dashboards
│   └── jwt/             RSA keypair output of `make jwt-keys`
├── load-tests/          k6 (seat_map_load.js, hold_burst.js)
├── docs/                SPEC.md, ADR, ER + BPMN + sequence diagrams (Mermaid + PNG)
├── report/              report.typ → report.pdf (course design report)
├── docker-compose.yml
├── Dockerfile           backend image (multi-stage)
├── Makefile             validate, smoke, jwt-keys, certs, compose-* targets
└── pom.xml              Spring Boot 3.3.5, Java 21
```

## Notes and current limitations

- **Payment provider is mocked.** `POST /bookings` accepts payment tokens
  `MOCK_PAY_OK / MOCK_PAY_DECLINED / MOCK_PAY_TIMEOUT`. No real PSP integration.
- **TLS cert is self-signed** (`make certs`, CN `tickets.local`, 365 d). A
  production deploy would terminate TLS at a managed certificate
  (Let's Encrypt / DNS-01).
- **Single Redis primary.** No Cluster or Sentinel; documented failure mode is
  `APP_REDIS_FAIL_CLOSED=true` → HTTP 503 on the write path while reads keep
  serving from Postgres.
- **Postgres and Redis are internal-only** in the published Compose. To inspect
  them locally, add a `ports:` block or use `docker exec`.
- **No GitHub Actions workflow yet.** `make validate` is the local CI
  equivalent.

## License & course attribution

Coursework for INHA Tashkent CSE *Database Application and Design*, Spring
2026. Submitted by *Team SIMPLE* — see `report/report.pdf` (cover page) for
the full roster. Instructor: Dr. Sarvar Abdullaev.
