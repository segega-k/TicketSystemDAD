# Technical Specification v3 — Ticketing and Event Booking System

> **Course:** Database Application and Design, Spring 2026
> **Scenario:** Ticketing and event booking
> **Audience:** This document is an authoritative build specification for **Claude Code**. Every decision is final. Implement exactly what is written; if anything appears internally inconsistent, halt and surface the question — do not improvise.
> **Coverage:** R1–R13 mapped in §30.

---

## 0. How to Read This Document

- §1–§13 follow the project rubric R1–R13.
- §14–§15 cover frontend and end-to-end flows.
- §16–§21 are Claude-Code-facing implementation guidance.
- §22–§29 are **complete inline source artefacts** (docker-compose.yml, application.yaml, Dockerfiles, Makefile, problem-type catalog, security details, PDF and dashboard specs). Treat them as the canonical text to put into the repository.
- §30 is the rubric-compliance checklist.

**Glossary**

- **MUST / SHOULD / MAY** — RFC 2119 conformance language.
- **Hold** — temporary multi-seat reservation in Redis; holds expire after 600 s.
- **Hold group** — one user's set of seats reserved in a single API call.
- **Booking** — durable row in Postgres; created when a hold is paid for.

---

## 1. Business Scenario and Requirements (R1)

### 1.1. Scenario

A ticketing platform for events (concerts, theatre, sports). Organizers create events with a predefined seat map and pricing. Customers select 1–6 adjacent seats on an interactive map, hold them in Redis during payment, and on payment success the hold is converted into confirmed bookings. The hard constraint is correctness during a flash-sale: thousands of concurrent users target the same seats and double-booking is unacceptable.

### 1.2. Actors

- **Guest** — anonymous, browses events only.
- **Customer** — registered, holds and pays for seats.
- **Organizer** — creates events, uploads seat maps, views sales dashboard for own events.
- **Analyst** — reads `analytics.event_daily_sales` and `audit_events`.
- **System administrator** — operates infrastructure and observability.

### 1.3. Functional Use Cases

| ID   | Use case                                                 | Actor             | Primary flow                                              |
| ---- | -------------------------------------------------------- | ----------------- | --------------------------------------------------------- |
| UC1  | Browse events with fuzzy search and cursor pagination    | Guest, Customer   | `GET /api/v1/events?q=&cursor=&limit=`                    |
| UC2  | View seat map with live updates                          | Guest, Customer   | Initial GET + WebSocket subscription                      |
| UC3  | Hold 1–6 adjacent seats atomically                       | Customer          | `POST /api/v1/seats/hold` with rate-limit + per-event cap |
| UC4  | Confirm booking (mock payment)                           | Customer          | `POST /api/v1/bookings` with `Idempotency-Key`            |
| UC5  | View own booking history                                 | Customer          | `GET /api/v1/users/me/bookings`                           |
| UC6  | Cancel a confirmed booking and receive a recorded refund | Customer          | `POST /api/v1/bookings/{id}/cancel`                       |
| UC7  | Download QR-coded ticket PDF                             | Customer          | `GET /api/v1/bookings/{id}/ticket.pdf`                    |
| UC8  | Create event with generated seat map                     | Organizer         | `POST /api/v1/events` with rows config                    |
| UC9  | View organizer dashboard                                 | Organizer         | `GET /api/v1/organizer/events/{id}/dashboard`             |
| UC10 | Nightly analytics aggregation                            | Analyst (passive) | Spring Batch → `analytics.event_daily_sales`              |
| UC11 | Audit trail                                              | Analyst, Sysadmin | Append-only `audit_events`                                |

### 1.4. Anti-Scalper Controls

- **Per-request rate limit:** 10 hold attempts / minute / user (R11 token-bucket; §11).
- **Per-event seat cap:** a single user MUST NOT simultaneously hold and/or own more than **6 seats** in the same event. Enforced atomically inside the hold-acquire Lua script (§5.3) and re-checked at booking commit.

### 1.5. Non-Functional Requirements

| Category          | Target                                                                                                                                                                                                        |
| ----------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Scale**         | 50,000 registered users; 5,000 concurrent WebSocket sessions per event; 500 RPS sustained on `POST /seats/hold`                                                                                               |
| **Latency (p95)** | GET seat map (cache hit) < 80 ms; POST hold < 150 ms; broadcast from commit to subscriber < 300 ms; POST booking < 250 ms                                                                                     |
| **Availability**  | 99.5 % SLO; on Redis outage, write paths return 503 and read paths fall back to Postgres                                                                                                                      |
| **Consistency**   | Strong for bookings (Postgres ACID + hold re-verified inside transaction); eventual for analytics and cache; at-least-once for WebSocket broadcasts via transactional outbox (§5.4)                           |
| **Security**      | JWT RS256, 30-min access + 7-day refresh; bcrypt(cost=12); HTTPS at gateway; CORS allowlist; CSP / HSTS / X-Frame-Options / X-Content-Type-Options headers; max request body 256 KB (1 MB for `POST /events`) |
| **Observability** | `trace_id` propagated through gateway → backend → Postgres/Redis; logs, metrics, traces in one Grafana backend                                                                                                |

---

## 2. Data Model and Architecture Diagrams (R2)

Required hand-designed diagrams (forbidden to auto-generate from Hibernate):

1. **ER diagram** — all 8 Postgres tables and their relationships.
2. **System architecture** — frontend, nginx, 2 backend replicas, Postgres, Redis (cache + locks + Pub/Sub channel), batch runner, OTel collector, Prometheus, Loki, Tempo, Grafana.
3. **Project structure** — monorepo tree (§17).
4. **Docker-compose dependency graph** — derived from `depends_on` + healthchecks.
5. **BPMN diagrams** — one per pipeline workflow (§10).
6. **Sequence diagram** — "Hold → Pay → Confirm → Broadcast" including outbox dispatcher and Redis Pub/Sub fan-out.

### 2.1. Entity Relationships

- `User 1..* Booking`
- `User 1..* HoldGroup` (in Redis, ephemeral)
- `Event 1..* Seat`
- `Event 1..* Booking`
- `Seat 1..1 Booking` for `CONFIRMED` status (partial unique index; §6.1)
- `Booking 1..1 Refund` (optional)
- `Booking 1..* AuditEvent`

---

## 3. Relational Database (R3)

### 3.1. DBMS and Tooling

- **PostgreSQL 16** with extensions `pgcrypto` and `pg_trgm`.
- **Flyway 10.18** — versioned migrations in `/migrations`.
- **Seed data** — `/migrations/afterMigrate__seed.sql` (Flyway callback).
- **One-command bring-up:** `make db-up`.

### 3.2. Full Schema

```sql
-- V001__extensions.sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- V002__core_tables.sql
CREATE TABLE users (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  username        VARCHAR(50)  NOT NULL UNIQUE,
  email           VARCHAR(255) NOT NULL UNIQUE,
  password_hash   VARCHAR(60)  NOT NULL,
  full_name       VARCHAR(120) NOT NULL,
  role            VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER'
                    CHECK (role IN ('CUSTOMER','ORGANIZER','ADMIN')),
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash      VARCHAR(64)  NOT NULL UNIQUE,         -- SHA-256 hex
  expires_at      TIMESTAMPTZ  NOT NULL,
  revoked_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE events (
  id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
  organizer_id    UUID         NOT NULL REFERENCES users(id),
  name            VARCHAR(200) NOT NULL,
  description     TEXT,
  event_date      TIMESTAMPTZ  NOT NULL,
  venue_name      VARCHAR(200) NOT NULL,
  status          VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED'
                    CHECK (status IN ('SCHEDULED','CANCELLED','COMPLETED')),
  total_seats     INT          NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TYPE seat_status AS ENUM ('AVAILABLE','BOOKED');

CREATE TABLE seats (
  id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id        UUID          NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  row_label       VARCHAR(10)   NOT NULL,
  seat_number     INT           NOT NULL,
  price           NUMERIC(10,2) NOT NULL CHECK (price >= 0),
  tier            VARCHAR(20)   NOT NULL DEFAULT 'STANDARD'
                    CHECK (tier IN ('VIP','STANDARD','ECONOMY')),
  status          seat_status   NOT NULL DEFAULT 'AVAILABLE',
  version         INT           NOT NULL DEFAULT 0,
  UNIQUE (event_id, row_label, seat_number)
);

CREATE TYPE booking_status AS ENUM ('CONFIRMED','CANCELLED');

CREATE TABLE bookings (
  id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID           NOT NULL REFERENCES users(id),
  event_id        UUID           NOT NULL REFERENCES events(id),
  seat_id         UUID           NOT NULL REFERENCES seats(id),
  hold_group_id   UUID           NOT NULL,
  booking_status  booking_status NOT NULL,
  amount          NUMERIC(10,2)  NOT NULL,
  idempotency_key VARCHAR(80),
  created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
  cancelled_at    TIMESTAMPTZ,
  UNIQUE (user_id, idempotency_key)
);

CREATE TABLE refunds (
  id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
  booking_id      UUID          NOT NULL UNIQUE REFERENCES bookings(id),
  amount          NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
  reason          VARCHAR(200),
  refunded_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- Transactional outbox (§5.4)
CREATE TABLE outbox_events (
  id              BIGSERIAL    PRIMARY KEY,
  aggregate_type  VARCHAR(40)  NOT NULL,
  aggregate_id    UUID         NOT NULL,
  event_type      VARCHAR(40)  NOT NULL,
  payload         JSONB        NOT NULL,
  trace_id        VARCHAR(64),
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
  dispatched_at   TIMESTAMPTZ,
  attempts        INT          NOT NULL DEFAULT 0
);

CREATE TABLE audit_events (
  id              BIGSERIAL    PRIMARY KEY,
  actor_user_id   UUID         REFERENCES users(id),
  entity_type     VARCHAR(40)  NOT NULL,
  entity_id       UUID         NOT NULL,
  action          VARCHAR(40)  NOT NULL,
  metadata        JSONB,
  trace_id        VARCHAR(64),
  created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- V003__indexes.sql
CREATE INDEX idx_events_date    ON events(event_date)
  WHERE event_date >= now() AND status = 'SCHEDULED';
CREATE INDEX idx_events_name_trgm ON events USING GIN (name gin_trgm_ops);
CREATE INDEX idx_seats_event_status ON seats(event_id, status);
CREATE INDEX idx_bookings_user_created ON bookings(user_id, created_at DESC);
CREATE INDEX idx_bookings_event ON bookings(event_id);
CREATE UNIQUE INDEX uniq_seat_confirmed
  ON bookings(seat_id) WHERE booking_status = 'CONFIRMED';
CREATE INDEX idx_outbox_undispatched ON outbox_events(id)
  WHERE dispatched_at IS NULL;
CREATE INDEX idx_audit_entity ON audit_events(entity_type, entity_id, created_at DESC);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id)
  WHERE revoked_at IS NULL;

-- V004__analytics_schema.sql
CREATE SCHEMA analytics;
CREATE TABLE analytics.event_daily_sales (
  event_id           UUID NOT NULL,
  sales_date         DATE NOT NULL,
  tickets_sold       INT  NOT NULL,
  tickets_cancelled  INT  NOT NULL DEFAULT 0,
  revenue            NUMERIC(12,2) NOT NULL,
  refunds_amount     NUMERIC(12,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (event_id, sales_date)
);
```

### 3.3. Seed Data

`afterMigrate__seed.sql` creates: 1 admin, 2 organizers, 5 customers, 3 events, ~600 seats total (3 × 200 seats with rows A–J × 20 numbers). Passwords are bcrypt of `password` (already pre-computed in the SQL).

---

## 4. RESTful API (R4)

### 4.1. Backend Stack (versions are fixed in §18)

Java 17 (Temurin), Spring Boot 3.3.4, Maven 3.9, Spring Web MVC, Spring Security 6, Spring Data JPA, Spring WebSocket, Spring Data Redis, springdoc-openapi 2.5, jjwt 0.12, MapStruct 1.5, Lombok 1.18, OpenPDF + ZXing for tickets.

### 4.2. Endpoint Catalogue

All endpoints under `/api/v1`. Errors follow RFC 7807 (§26).

| #   | Method | Path                                | Auth                         | Idempotent | Success               | Errors                  |
| --- | ------ | ----------------------------------- | ---------------------------- | ---------- | --------------------- | ----------------------- |
| 1   | POST   | `/auth/register`                    | —                            | No         | 201                   | 400, 409                |
| 2   | POST   | `/auth/login`                       | —                            | No         | 200                   | 401                     |
| 3   | POST   | `/auth/refresh`                     | refresh JWT                  | No         | 200                   | 401                     |
| 4   | POST   | `/auth/logout`                      | refresh JWT                  | Yes        | 204                   | 401                     |
| 5   | GET    | `/events?q=&cursor=&limit=`         | —                            | Yes        | 200                   | 400                     |
| 6   | GET    | `/events/{id}`                      | —                            | Yes        | 200                   | 404                     |
| 7   | POST   | `/events`                           | ORGANIZER                    | No         | 201                   | 400, 401, 403           |
| 8   | GET    | `/events/{id}/seats`                | —                            | Yes        | 200                   | 404                     |
| 9   | POST   | `/seats/hold`                       | CUSTOMER                     | semantic¹  | 200                   | 400, 401, 409, 422, 429 |
| 10  | DELETE | `/seats/hold/{holdGroupId}`         | CUSTOMER (owner)             | Yes        | 204                   | 401, 403, 404           |
| 11  | POST   | `/bookings`                         | CUSTOMER + `Idempotency-Key` | Yes²       | 201                   | 400, 401, 409, 422      |
| 12  | GET    | `/bookings/{id}`                    | CUSTOMER (owner)             | Yes        | 200                   | 401, 403, 404           |
| 13  | GET    | `/bookings/{id}/ticket.pdf`         | CUSTOMER (owner)             | Yes        | 200 (application/pdf) | 401, 403, 404, 409      |
| 14  | POST   | `/bookings/{id}/cancel`             | CUSTOMER (owner)             | No         | 200                   | 401, 403, 404, 409      |
| 15  | GET    | `/users/me/bookings?cursor=&limit=` | CUSTOMER                     | Yes        | 200                   | 401                     |
| 16  | GET    | `/organizer/events/{id}/dashboard`  | ORGANIZER (owner)            | Yes        | 200                   | 401, 403, 404           |
| 17  | GET    | `/actuator/health`                  | —                            | Yes        | 200, 503              | —                       |
| 18  | GET    | `/actuator/prometheus`              | internal                     | Yes        | 200                   | —                       |

¹ Calling `POST /seats/hold` again with the same `(user_id, event_id, seat_ids)` while an active hold exists returns the existing hold (same `hold_group_id`, same `hold_token`).
² Server stores `(user_id, idempotency_key)` for 24 h; repeat returns original response.

### 4.3. Concrete Request/Response Examples (all 16 endpoints)

**(1) POST `/api/v1/auth/register`**

```json
// Request
{"username":"alice","email":"alice@example.com","password":"Secret123!","full_name":"Alice Anderson"}
// 201 Created
{"id":"...","username":"alice","email":"alice@example.com","role":"CUSTOMER","created_at":"2026-05-16T10:00:00Z"}
```

**(2) POST `/api/v1/auth/login`**

```json
// Request
{"email":"alice@example.com","password":"Secret123!"}
// 200 OK
{"access_token":"eyJhbGciOi...","refresh_token":"a7f4...","token_type":"Bearer","expires_in":1800}
```

**(3) POST `/api/v1/auth/refresh`**

```json
// Request
{"refresh_token":"a7f4..."}
// 200 OK
{"access_token":"eyJhbGciOi...","refresh_token":"b8e5...","token_type":"Bearer","expires_in":1800}
```

**(4) POST `/api/v1/auth/logout`**

```
// Request: header X-Refresh-Token: a7f4...
// 204 No Content
```

**(5) GET `/api/v1/events?q=mozart&cursor=&limit=20`**

```json
// 200 OK
{
  "items": [
    {
      "id": "...",
      "name": "Mozart Gala",
      "event_date": "2026-06-01T19:00:00Z",
      "venue_name": "Inha Hall",
      "total_seats": 200,
      "min_price": "25.00",
      "max_price": "120.00",
      "status": "SCHEDULED"
    }
  ],
  "next_cursor": "eyJpZCI6Ii4uLiJ9"
}
```

**(6) GET `/api/v1/events/{id}`**

```json
// 200 OK
{
  "id": "...",
  "name": "Mozart Gala",
  "description": "Full orchestra...",
  "event_date": "2026-06-01T19:00:00Z",
  "venue_name": "Inha Hall",
  "organizer": { "id": "...", "full_name": "Acme Productions" },
  "status": "SCHEDULED",
  "seats_summary": { "total": 200, "available": 143, "booked": 57 }
}
```

**(7) POST `/api/v1/events`**

```json
// Request — seat map is generated from a rows config
{
  "name":"Mozart Gala",
  "description":"Full orchestra",
  "event_date":"2026-06-01T19:00:00Z",
  "venue_name":"Inha Hall",
  "rows":[
    {"label":"A","seat_count":20,"tier":"VIP","price":"120.00"},
    {"label":"B","seat_count":20,"tier":"VIP","price":"100.00"},
    {"label":"C","seat_count":20,"tier":"STANDARD","price":"60.00"},
    {"label":"D","seat_count":20,"tier":"STANDARD","price":"60.00"},
    {"label":"E","seat_count":20,"tier":"ECONOMY","price":"25.00"}
  ]
}
// 201 Created
{"id":"...","total_seats":100,"name":"Mozart Gala"}
```

**(8) GET `/api/v1/events/{id}/seats`**

```json
// 200 OK
{
  "event_id": "...",
  "rows": [
    {
      "label": "A",
      "seats": [
        { "id": "...", "number": 1, "tier": "VIP", "price": "120.00", "status": "AVAILABLE" },
        { "id": "...", "number": 2, "tier": "VIP", "price": "120.00", "status": "HELD" },
        { "id": "...", "number": 3, "tier": "VIP", "price": "120.00", "status": "BOOKED" }
      ]
    }
  ],
  "fetched_at": "2026-05-16T10:00:00Z"
}
```

> **Status mapping in this response:** `BOOKED` if `seats.status='BOOKED'` in Postgres; `HELD` if a key `hold:seat:{id}` exists in Redis; `AVAILABLE` otherwise.

**(9) POST `/api/v1/seats/hold`**

```json
// Request (header: Authorization: Bearer <jwt>)
{"event_id":"...","seat_ids":["...","..."]}
// 200 OK
{
  "hold_group_id":"...",
  "hold_token":"TKN_8f3b2a1c9d4e6f7a",
  "expires_at":"2026-05-16T10:10:00Z",
  "ttl_seconds":600,
  "seats":[
    {"id":"...","row":"A","number":1,"price":"120.00"},
    {"id":"...","row":"A","number":2,"price":"120.00"}
  ],
  "total_amount":"240.00"
}
// 409 Conflict — at least one seat held by someone else
{"type":"https://tickets.inha.uz/problems/seat-already-held","title":"Seat already held","status":409,"detail":"Seat aaa... is currently held by another user.","conflicting_seat_ids":["aaa..."]}
// 422 Unprocessable — would exceed 6-seat cap
{"type":"https://tickets.inha.uz/problems/seat-cap-exceeded","title":"Per-event seat cap exceeded","status":422,"detail":"You already hold or own 5 seats for this event; requested 2 more would exceed the cap of 6."}
// 429 Too Many Requests
{"type":"https://tickets.inha.uz/problems/rate-limited","title":"Too many hold attempts","status":429,"detail":"Try again in 12 seconds.","retry_after_seconds":12}
```

**(10) DELETE `/api/v1/seats/hold/{holdGroupId}`** → 204.

**(11) POST `/api/v1/bookings`**

```json
// Request (headers: Authorization: Bearer <jwt>, Idempotency-Key: client-uuid-v4)
{"hold_group_id":"...","hold_token":"TKN_8f3b...","payment_token":"MOCK_PAY_OK"}
// 201 Created
{
  "booking_group_id":"...",
  "bookings":[
    {"id":"...","seat_id":"...","row":"A","number":1,"amount":"120.00"},
    {"id":"...","seat_id":"...","row":"A","number":2,"amount":"120.00"}
  ],
  "total_amount":"240.00",
  "created_at":"2026-05-16T10:05:00Z",
  "ticket_pdf_urls":["/api/v1/bookings/.../ticket.pdf","/api/v1/bookings/.../ticket.pdf"]
}
// 409 — hold expired or token mismatch
{"type":"https://tickets.inha.uz/problems/hold-not-found","title":"Hold expired or invalid","status":409,"detail":"The hold has expired or the token does not match."}
```

**Mock payment tokens (deterministic for tests):**

- `MOCK_PAY_OK` → succeeds.
- `MOCK_PAY_DECLINED` → 422 with `payment-declined`.
- `MOCK_PAY_TIMEOUT` → 504 (used in chaos tests only).

**(12) GET `/api/v1/bookings/{id}`** → single booking JSON, fields as in (11).

**(13) GET `/api/v1/bookings/{id}/ticket.pdf`** → `Content-Type: application/pdf`, `Content-Disposition: attachment; filename="ticket-{id}.pdf"`. Layout in §28.

**(14) POST `/api/v1/bookings/{id}/cancel`**

```json
// Request
{"reason":"Changed plans"}
// 200 OK
{"booking_id":"...","booking_status":"CANCELLED","cancelled_at":"...","refund":{"id":"...","amount":"120.00","refunded_at":"..."}}
// 409 — event already started or booking already cancelled
{"type":"https://tickets.inha.uz/problems/cancellation-not-allowed","title":"Cancellation not allowed","status":409,"detail":"Event starts in less than 24 hours."}
```

> Business rule: cancellation allowed only if `event_date - now() >= 24h` AND booking is `CONFIRMED`. Refund amount equals booking amount (100% in this academic project).

**(15) GET `/api/v1/users/me/bookings?cursor=&limit=20`** — cursor-paginated list of (12) shape.

**(16) GET `/api/v1/organizer/events/{id}/dashboard`** — see §29 for the SQL and the response shape.

### 4.4. Cursor Pagination

Cursor is base64url-JSON `{"id":"<uuid>","created_at":"<iso>"}` of the last item from the previous page. Server query uses `WHERE (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?`. `limit` range: 1–100, default 20.

### 4.5. OpenAPI

springdoc-openapi configuration in `application.yaml` (§23). Swagger UI at `/swagger-ui.html`, raw JSON at `/v3/api-docs`. Every endpoint has `@Operation`, every DTO has `@Schema`, every error path documents at least one example.

---

## 5. Polyglot Persistence (R5) — Redis 7

### 5.1. Justification (one-page rationale required in the report)

Redis fills four roles unsuited to Postgres:

1. **Distributed multi-seat hold** — atomic compare-and-set with TTL across N keys via Lua (§5.3). A Postgres `SELECT ... FOR UPDATE` would hold a connection for up to 10 minutes; the connection pool would exhaust in seconds at the target 500 RPS.
2. **Read-through cache** for hot event and seat-map reads (~5× p95 improvement, §6.3).
3. **State store for the rate limiter** (§11), shared across both backend replicas.
4. **Pub/Sub fan-out** that lets every backend replica reach its WebSocket clients with state changes from the other replica (§7.2).

### 5.2. Key Layout

| Key                                   | Type          | Value                                                        | TTL     | Purpose                          |
| ------------------------------------- | ------------- | ------------------------------------------------------------ | ------- | -------------------------------- |
| `hold:group:{hold_group_id}`          | hash          | `user_id`, `event_id`, `seat_ids_csv`, `token`, `created_at` | 600 s   | Hold metadata                    |
| `hold:seat:{seat_id}`                 | string        | `{hold_group_id}`                                            | 600 s   | Per-seat marker for atomic check |
| `purchase:count:{user_id}:{event_id}` | int (string)  | held + owned seats count                                     | 24 h    | 6-seat cap enforcement           |
| `cache:event:{event_id}`              | string (JSON) | Event DTO                                                    | 300 s   | Read-through cache               |
| `cache:seats:{event_id}`              | string (JSON) | seat-map snapshot                                            | 60 s    | Read-through cache               |
| `cache:events:page:{cursor_hash}`     | string (JSON) | events list page                                             | 30 s    | Read-through cache               |
| `ratelimit:hold:{user_id}`            | hash          | `tokens`, `ts`                                               | 120 s   | Token-bucket state (R11)         |
| `idem:booking:{user_id}:{key}`        | string (JSON) | original 201 response                                        | 86400 s | `Idempotency-Key` cache          |
| `ws:presence:{event_id}`              | set           | user_ids viewing map                                         | —       | Optional, observability only     |

Channel for Pub/Sub: `ws.broadcast` — payload is the same JSON message that will be sent to STOMP topic.

### 5.3. Hold-Acquire — Full Lua Script (`/backend/src/main/resources/redis/hold_acquire.lua`)

```lua
-- KEYS layout:
--   KEYS[1]                 = hold:group:{hold_group_id}
--   KEYS[2]                 = purchase:count:{user_id}:{event_id}
--   KEYS[3..3+N-1]          = hold:seat:{seat_id} for each of N seats
-- ARGV layout:
--   ARGV[1] = user_id (uuid string)
--   ARGV[2] = event_id (uuid string)
--   ARGV[3] = hold_token (opaque string)
--   ARGV[4] = seat_ids_csv (comma-separated uuids, same order as KEYS[3..])
--   ARGV[5] = ttl_seconds (int)
--   ARGV[6] = max_per_user (int, =6)
--   ARGV[7] = now_iso (string)

local group_key       = KEYS[1]
local count_key       = KEYS[2]
local ttl             = tonumber(ARGV[5])
local max_per_user    = tonumber(ARGV[6])
local n_seats         = #KEYS - 2

-- 1) Verify per-event cap
local current = tonumber(redis.call('GET', count_key) or '0')
if current + n_seats > max_per_user then
  return {0, 'CAP_EXCEEDED', tostring(current), tostring(n_seats)}
end

-- 2) Check every seat is free (no existing hold:seat:* key)
local conflicts = {}
for i = 3, #KEYS do
  if redis.call('EXISTS', KEYS[i]) == 1 then
    conflicts[#conflicts + 1] = KEYS[i]
  end
end
if #conflicts > 0 then
  return {0, 'SEAT_CONFLICT', table.concat(conflicts, ',')}
end

-- 3) All checks passed — atomically set everything
for i = 3, #KEYS do
  redis.call('SET', KEYS[i], group_key, 'EX', ttl)
end

redis.call('HMSET', group_key,
  'user_id',     ARGV[1],
  'event_id',    ARGV[2],
  'seat_ids',    ARGV[4],
  'token',       ARGV[3],
  'created_at',  ARGV[7])
redis.call('EXPIRE', group_key, ttl)

redis.call('INCRBY', count_key, n_seats)
redis.call('EXPIRE', count_key, 86400)

return {1, 'OK'}
```

Loaded at startup via `SCRIPT LOAD`; the SHA1 is cached in `HoldAcquireScript` bean. Calls use `EVALSHA` with a `NOSCRIPT` fallback to re-`LOAD`.

### 5.4. Transactional Outbox (correctness fix)

**Problem.** If the Postgres booking transaction commits but the process crashes before publishing the WebSocket broadcast, connected clients see stale state until cache TTL expires.

**Solution.** Inside the same transaction that writes bookings, write to `outbox_events`. A `OutboxDispatcher` scheduled bean polls every 200 ms:

```sql
SELECT id, aggregate_type, aggregate_id, event_type, payload, trace_id
FROM outbox_events
WHERE dispatched_at IS NULL
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

For each row: publish to Redis Pub/Sub channel `ws.broadcast`, then `UPDATE outbox_events SET dispatched_at = now(), attempts = attempts + 1 WHERE id = ?`. On Redis failure, the row stays undispatched and the next tick retries; metric `outbox_dispatch_failures_total` increments. Polling parameters in `application.yaml` (§23).

**Guarantees.** At-least-once delivery to the channel. Consumers are idempotent because messages are state snapshots, not deltas (`{seat_id, status: BOOKED}` — applying it twice has the same effect as once).

**Backpressure / alerting.** If `SELECT count(*) FROM outbox_events WHERE dispatched_at IS NULL > 1000`, the reconciliation batch (§10.3) emits a Prometheus alert.

### 5.5. Redis Keyspace Notifications (UX improvement)

Redis is configured with `notify-keyspace-events Ex` (key-expired events). A `KeyExpirationListener` bean subscribes to `__keyevent@0__:expired`; for every expiring `hold:seat:{seat_id}` it inserts an outbox row `SEAT_AVAILABLE` (and decrements `purchase:count` accordingly). This delivers expiry visibility to clients in ~100 ms instead of ~5 minutes.

---

## 6. Cache, Indexing, and Optimisation (R6)

### 6.1. Postgres Indexes

All indexes defined in `V003__indexes.sql` (§3.2). Particularly load-bearing:

- `idx_seats_event_status` — seat-map query.
- `uniq_seat_confirmed` (partial unique) — hard guarantee against double-booking at the database level (even if every other safeguard fails).
- `idx_outbox_undispatched` (partial) — keeps dispatcher polling O(batch size) not O(total).
- `idx_events_name_trgm` (GIN) — fuzzy search for UC1.

### 6.2. Caching Strategy

- **Read-through** on GET events list, GET event detail, GET seat map.
- **Write-through invalidation**: state-changing operations also `DEL cache:seats:{event_id}` and `DEL cache:event:{event_id}`. Failure to delete is best-effort; TTL is the safety net.
- **Cursor pagination cache keying**: page key is `cache:events:page:{sha256(query+cursor+limit)}`.

### 6.3. Quantitative Measurement (required by R6)

Run before and after enabling Redis caches:

```bash
make load-test-seat-map
```

`/load-tests/seat_map_load.js` (k6) hits `GET /events/{id}/seats` with 100 VUs for 60 s and writes a JSON report under `/load-tests/results/`. Acceptance threshold (§21 R6 Done): **p95 must improve ≥ 5×** between the two runs.

---

## 7. Additional API Style: WebSocket (R7)

### 7.1. Justification

Polling REST at 1 Hz × 5,000 viewers = 5,000 wasted RPS per event. WebSocket pushes only on real state change.

### 7.2. Cross-Replica Broadcast via Redis Pub/Sub (concrete design)

Spring's default `SimpleBroker` is in-process: a broadcast issued on `backend-1` does not reach clients connected to `backend-2`. **Rather than configuring an external STOMP broker, we keep `SimpleBroker` on every replica and bridge them via Redis Pub/Sub:**

1. The outbox dispatcher publishes every state-change message to channel `ws.broadcast` in Redis (§5.4).
2. Every backend replica registers a `RedisMessageListenerContainer` subscribed to `ws.broadcast`.
3. On message receipt the listener calls `SimpMessagingTemplate.convertAndSend("/topic/events/{event_id}/seats", payload)`.
4. Net effect: each replica forwards the broadcast to its own STOMP-subscribed clients; Redis Pub/Sub fans out to all replicas.

This works because:

- Every WebSocket client is attached to exactly one replica via nginx `ip_hash` (§8).
- Redis Pub/Sub delivers to every subscriber that is currently connected, with at-most-once semantics per delivery (acceptable because outbox guarantees the publish happens; missed Pub/Sub messages during a replica restart are tolerated — clients re-fetch on reconnect).
- No external STOMP broker (RabbitMQ/ActiveMQ) is needed.

### 7.3. Protocol Details

- WebSocket endpoint: `wss://<host>/ws`.
- STOMP topic: `/topic/events/{event_id}/seats`.
- Auth: STOMP `CONNECT` frame must carry `Authorization: Bearer <jwt>` header; rejected otherwise.
- Heartbeat: 10s/10s.
- Message format:

```json
{
  "event_id": "...",
  "seat_id": "...",
  "status": "AVAILABLE|HELD|BOOKED",
  "ts": "2026-05-16T10:30:01Z",
  "trace_id": "0af7651916cd43dd8448eb211c80319c"
}
```

- Reconnect strategy: client uses exponential backoff (1s, 2s, 4s, …, max 30s). On reconnect, client re-fetches the full seat map via REST to recover from any missed messages.

---

## 8. API Gateway and Load Balancing (R8)

Full nginx config in §27.

- TLS termination (self-signed in dev via `make certs`, Let's Encrypt in prod).
- `least_conn` for HTTP, `ip_hash` for `/ws/*` (sticky sessions).
- Headers added: `Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Content-Security-Policy: default-src 'self'`.
- Generates `X-Request-Id` if absent (used as trace seed).
- Two backend replicas: `backend-1:8080`, `backend-2:8080`.

---

## 9. Docker Compose Orchestration (R9)

Full `docker-compose.yml` in §22. Public surface area is one TLS port (443). All other services on the internal Docker network.

Mandatory:

- Health checks on Postgres, Redis, both backends.
- Named volumes: `pgdata`, `redisdata`, `prometheusdata`, `lokidata`, `tempodata`, `grafanadata`.
- `depends_on` with `condition: service_healthy`.
- One-command bring-up: `make up` (`docker compose up -d --build`).

---

## 10. Batch Pipeline and BPMN (R10)

Spring Batch lives in a dedicated `batch/` service (separate Spring Boot app, shares migrations and entity models). Quartz scheduler inside.

### 10.1. Workflow A: Daily Sales Aggregation (cron `0 0 2 * * *`)

Reader: `JdbcCursorItemReader` selecting bookings whose `created_at::date = (now() - 1 day)` OR `cancelled_at::date = (now() - 1 day)`. Processor: group by `event_id`, compute `tickets_sold`, `tickets_cancelled`, `revenue` (sum of CONFIRMED amounts), `refunds_amount` (sum of refunds). Writer: `INSERT INTO analytics.event_daily_sales ... ON CONFLICT (event_id, sales_date) DO UPDATE`. Spring Batch chunk size 100; restart from last commit on failure.

### 10.2. Workflow B: Outbox Reconciliation (cron `0 */10 * * * *`)

Finds `outbox_events` with `dispatched_at IS NULL AND created_at < now() - interval '1 minute' AND attempts < 10`. Republishes via the same `OutboxDispatcher.publish(row)` path; if `attempts >= 10`, marks `dispatched_at = now()` with `attempts = 99` (poison-pill) and emits `outbox_poison_total` counter. If undispatched backlog > 1000, fires Prometheus alert `OutboxBacklogHigh`.

### 10.3. BPMN Diagrams (mandatory)

Drawn in [bpmn.io](https://bpmn.io) and committed under `/docs/bpmn/`. Filenames: `daily_sales.bpmn`, `outbox_reconciliation.bpmn`. PNG renders embedded in the report.

---

## 11. From-Scratch Component: Token-Bucket Rate Limiter (R11)

Already complete in v2. Source at `/backend/src/main/java/uz/inha/tickets/ratelimit/` with its own README. Lua script `/backend/src/main/resources/redis/token_bucket.lua`:

```lua
-- KEYS[1] = ratelimit:hold:{user_id}
-- ARGV    = capacity, refill_per_sec, now_ms
local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens   = tonumber(b[1]) or tonumber(ARGV[1])
local last_ts  = tonumber(b[2]) or tonumber(ARGV[3])
local elapsed  = tonumber(ARGV[3]) - last_ts
local refilled = math.min(tonumber(ARGV[1]),
                          tokens + elapsed * tonumber(ARGV[2]) / 1000)
if refilled < 1 then return 0 end
redis.call('HMSET',  KEYS[1], 'tokens', refilled - 1, 'ts', ARGV[3])
redis.call('EXPIRE', KEYS[1], 120)
return 1
```

Capacity 10, refill rate 10/min. Filter: `RateLimitFilter extends OncePerRequestFilter`, registered for `POST /api/v1/seats/hold`. Reject → HTTP 429 + RFC 7807 body + `Retry-After` header (computed from current bucket state).

Metrics: `ratelimit_accepted_total{endpoint}`, `ratelimit_rejected_total{endpoint}`, `ratelimit_failopen_total{endpoint}`.

---

## 12. Observability (R12)

OpenTelemetry → Tempo (traces) + Loki (logs) + Prometheus (metrics) → Grafana.

### 12.1. OTel Agent Attachment

OTel Java agent attached via `JAVA_TOOL_OPTIONS` env in the backend Dockerfile (§25):

```
JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar
OTEL_SERVICE_NAME=tickets-backend
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
OTEL_METRICS_EXPORTER=none           # metrics via Micrometer/Prometheus
OTEL_LOGS_EXPORTER=otlp
OTEL_TRACES_EXPORTER=otlp
```

### 12.2. Logging Format

Logback configured to JSON via `logstash-logback-encoder`. Every line includes `trace_id`, `span_id`, `service`, `level`, `logger`, `message`, `mdc.*`. Config in `/backend/src/main/resources/logback-spring.xml` (concrete in §27).

### 12.3. Pre-Provisioned Grafana Dashboards

JSON in `/infra/grafana/dashboards/`:

- `api-overview.json` — request rate, p50/p95/p99 per endpoint, error rate.
- `bookings-funnel.json` — holds_requested vs holds_acquired vs bookings_confirmed vs cancellations.
- `ratelimit.json` — accepted vs rejected per user (top 10).
- `outbox.json` — dispatcher backlog, dispatch latency p95, attempts histogram.

### 12.4. Required Screenshots in the Report

1. A single trace of `POST /bookings` showing nginx + backend + Postgres + Redis spans + outbox insert + Pub/Sub publish.
2. Loki query `{service="tickets-backend"} |= "<trace_id>"` showing the corresponding log lines.
3. Grafana panel `http_server_requests_seconds{uri="/api/v1/seats/hold",quantile="0.95"}` over 1 h.

---

## 13. Documentation (R13)

- `/README.md` (top-level): prerequisites, `make up` walkthrough, demo credentials, links to Swagger UI and Grafana.
- `/CHANGELOG.md`: ≥ 3 semver entries.
- `/docs/diagrams/`: ER, system architecture, project structure, compose dependency graph, sequence — both source files (draw.io XML) and PNG exports.
- `/docs/bpmn/`: two BPMN files plus PNG renders.
- `/docs/adr/`: ADR-001 polyglot Postgres+Redis; ADR-002 STOMP via SimpleBroker + Redis Pub/Sub bridge; ADR-003 transactional outbox; ADR-004 hold-group model; ADR-005 token-bucket vs fixed-window.
- `/docs/report/`: final PDF report sources.
- Per-module READMEs under `ratelimit/`, `batch/`, `migrations/`, `infra/`.

`.gitignore` excludes: `target/`, `build/`, `node_modules/`, `dist/`, `.cache/`, `coverage/`, `*.log`, `.env`, `.idea/`, `.vscode/`, `*.swp`.

---

## 14. Frontend

React 18 + TypeScript + Vite 5 + Tailwind 3 + Zustand + Axios + `@stomp/stompjs` 7 + react-router-dom 6 + react-hook-form + zod.

**Route → API mapping:**

| Path                              | Component                 | Calls                                                                           |
| --------------------------------- | ------------------------- | ------------------------------------------------------------------------------- |
| `/`                               | LandingPage               | —                                                                               |
| `/login`                          | LoginPage                 | POST /auth/login                                                                |
| `/register`                       | RegisterPage              | POST /auth/register                                                             |
| `/events`                         | EventsListPage            | GET /events (cursor)                                                            |
| `/events/:id`                     | EventDetailPage + SeatMap | GET /events/:id, GET /events/:id/seats, WS /ws                                  |
| `/checkout/:holdGroupId`          | CheckoutPage              | POST /bookings                                                                  |
| `/me/bookings`                    | MyBookingsPage            | GET /users/me/bookings, GET /bookings/:id/ticket.pdf, POST /bookings/:id/cancel |
| `/organizer/events/:id/dashboard` | OrganizerDashboard        | GET /organizer/events/:id/dashboard                                             |

**Axios interceptor:** auto-attaches `Authorization: Bearer <access>` from Zustand `authStore`; on 401 attempts `POST /auth/refresh` once; on second 401 redirects to `/login`.

---

## 15. End-to-End "Hold → Pay → Confirm → Broadcast"

1. `GET /api/v1/events/{id}/seats` → Redis cache hit → seat map rendered.
2. Frontend opens `wss://<host>/ws`, subscribes `/topic/events/{id}/seats`.
3. User selects 2 adjacent seats → `POST /api/v1/seats/hold`.
4. `RateLimitFilter` runs token-bucket Lua. Reject → 429.
5. Backend runs hold-acquire Lua (§5.3) — atomic for N seats + per-user cap.
6. Backend opens Postgres transaction: `INSERT INTO outbox_events` (N rows, `SEAT_HELD`) + `INSERT INTO audit_events` (1 row, `HOLD_CREATED`). `COMMIT`.
7. `OutboxDispatcher` publishes to Redis Pub/Sub `ws.broadcast`; every backend replica's `RedisMessageListenerContainer` forwards via `SimpMessagingTemplate` to its STOMP clients.
8. User submits payment (mock) → `POST /api/v1/bookings` with `Idempotency-Key`.
9. Backend opens Postgres transaction:
   - Check `idem:booking:{user_id}:{key}` in Redis; if present, return cached response.
   - Re-validate hold in Redis (token matches, not expired).
   - `SELECT seats FOR UPDATE`.
   - `INSERT INTO bookings`.
   - `UPDATE seats SET status='BOOKED', version=version+1`.
   - `INSERT INTO outbox_events` (N rows, `SEAT_BOOKED`) + audit.
   - `COMMIT`.
10. Post-commit (best-effort): `DEL hold:seat:*`, `DEL hold:group:*`, `DECRBY purchase:count` by N (hold→booking is conservation, count stays the same — explicit no-op for clarity). Set `idem:booking:*` cache with 24h TTL.
11. Dispatcher publishes `SEAT_BOOKED`; clients flip seats to red.
12. Single `trace_id` covers the whole flow in Grafana.

---

## 16. Architectural Improvements Adopted

1. STOMP via SimpleBroker + Redis Pub/Sub bridge (§7.2) — no external broker, cross-replica delivery works.
2. Transactional outbox (§5.4) — at-least-once WebSocket broadcasts.
3. Redis keyspace notifications (§5.5) — sub-second hold-expiry UX.
4. Hold groups via single atomic Lua script (§5.3) — multi-seat purchases without partial holds.
5. Opaque `hold_token` (§4.3) — defends against cross-session unlock.
6. `Idempotency-Key` on bookings (§4.2) — safe payment retries.
7. Per-event 6-seat cap (§1.4) — anti-scalper layer above rate limit.
8. Cancellation/refund flow with dedicated `refunds` table (§3.2).
9. RFC 7807 problem details (§26) — unified error contract.

---

## 17. Repository Structure

```
ticketing/
├── README.md
├── CHANGELOG.md
├── Makefile
├── docker-compose.yml
├── .env.example
├── .gitignore
├── frontend/
│   ├── package.json
│   ├── vite.config.ts
│   ├── tailwind.config.js
│   ├── tsconfig.json
│   ├── Dockerfile
│   ├── nginx.conf
│   ├── index.html
│   └── src/
│       ├── main.tsx
│       ├── App.tsx
│       ├── api/{http.ts, auth.ts, events.ts, seats.ts, bookings.ts}
│       ├── store/{authStore.ts, selectionStore.ts}
│       ├── pages/{Login,Register,EventsList,EventDetail,Checkout,MyBookings,OrganizerDashboard}.tsx
│       ├── components/{SeatMap.tsx, SeatLegend.tsx, EventCard.tsx, ...}
│       └── ws/stompClient.ts
├── backend/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/java/uz/inha/tickets/
│       │   ├── TicketsApplication.java
│       │   ├── auth/{controllers, services, jwt, dto}
│       │   ├── events/{controllers, services, repositories, entities, dto}
│       │   ├── seats/{controllers, services, repositories, entities, dto}
│       │   ├── holds/{HoldService.java, HoldAcquireScript.java, dto}
│       │   ├── bookings/{controllers, services, repositories, entities, dto, IdempotencyService.java}
│       │   ├── refunds/{services, repositories, entities}
│       │   ├── tickets/{TicketPdfService.java}
│       │   ├── outbox/{OutboxDispatcher.java, OutboxRepository.java}
│       │   ├── ws/{WebSocketConfig.java, RedisStompBridge.java, KeyExpirationListener.java}
│       │   ├── ratelimit/{RateLimitFilter.java, TokenBucketScript.java, README.md}
│       │   ├── observability/{OtelConfig.java, LogMdcFilter.java}
│       │   ├── audit/{AuditService.java, AuditEvent.java}
│       │   ├── organizer/{OrganizerDashboardController.java, OrganizerDashboardService.java}
│       │   └── common/{ProblemDetailsAdvice.java, CursorPaging.java, RfcTypes.java}
│       ├── main/resources/
│       │   ├── application.yaml
│       │   ├── logback-spring.xml
│       │   └── redis/{hold_acquire.lua, token_bucket.lua}
│       └── test/java/uz/inha/tickets/...
├── batch/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/uz/inha/tickets/batch/
│       ├── BatchApplication.java
│       ├── daily_sales/
│       └── outbox_reconciliation/
├── migrations/
│   ├── V001__extensions.sql
│   ├── V002__core_tables.sql
│   ├── V003__indexes.sql
│   ├── V004__analytics_schema.sql
│   └── afterMigrate__seed.sql
├── infra/
│   ├── nginx/{default.conf, certs/}
│   ├── otel/otel-collector-config.yaml
│   ├── prometheus/prometheus.yml
│   ├── loki/loki-config.yaml
│   ├── tempo/tempo-config.yaml
│   └── grafana/
│       ├── provisioning/datasources/datasources.yaml
│       └── dashboards/{api-overview.json, bookings-funnel.json, ratelimit.json, outbox.json}
├── load-tests/
│   ├── seat_map_load.js
│   ├── hold_burst.js
│   └── results/
└── docs/
    ├── diagrams/
    ├── bpmn/
    ├── adr/
    └── report/
```

---

## 18. Pinned Dependency Versions

**Backend Maven deps** (already enumerated in v2 §18; unchanged here): Spring Boot 3.3.4, Flyway 10.18.0, jjwt 0.12.6, springdoc 2.5.0, MapStruct 1.5.5, Lombok 1.18.34, OpenPDF 2.0.3, ZXing 3.5.3, Postgres JDBC 42.7.4, Testcontainers 1.20.2, redis-testcontainers 2.2.2, OTel agent 2.7.0, logstash-logback-encoder 7.4.

**Frontend** (unchanged): react 18.3.1, vite 5.4.0, tailwind 3.4.10, zustand 4.5.5, axios 1.7.7, @stomp/stompjs 7.0.0, react-router-dom 6.26.1, react-hook-form 7.53.0, zod 3.23.8.

**Operational images**: postgres:16-alpine, redis:7-alpine, nginx:1.27-alpine, flyway/flyway:10.18, otel/opentelemetry-collector-contrib:0.103.0, prom/prometheus:v2.54.0, grafana/loki:3.1.0, grafana/tempo:2.5.0, grafana/grafana:11.1.0.

---

## 19. Build Order (Claude Code: implement strictly in this order)

1. Scaffolding: repo skeleton, Makefile (§24), `.env.example`, `.gitignore`, `docker-compose.yml` with Postgres+Redis only.
2. Migrations V001–V004 + seed; `make db-up` proves they apply.
3. Backend Spring Boot bootstrap: `TicketsApplication`, `application.yaml` (§23), Dockerfile (§25).
4. Common layer: ProblemDetails advice (§26), cursor pagination utility, RFC types constants.
5. Auth: register, login, refresh, logout, JWT keys generation script.
6. Events module: entities, repositories, controllers, cache, pg_trgm search.
7. Seats module: entity, seat-map read endpoint with cache.
8. Holds module + hold-acquire Lua + per-event cap + audit.
9. Rate limiter (R11) + token-bucket Lua + tests.
10. Bookings module + idempotency cache + outbox insert + audit.
11. Outbox dispatcher + Redis Pub/Sub publisher.
12. WebSocket: WebSocketConfig + RedisStompBridge listener + KeyExpirationListener.
13. Cancellation + refunds + PDF ticket service.
14. Organizer dashboard.
15. Frontend full app.
16. Nginx gateway (§27) + extend compose with second backend replica.
17. Batch service (daily sales + outbox reconciliation) + BPMN files.
18. Observability stack: OTel collector, Prometheus, Loki, Tempo, Grafana dashboards.
19. Load tests + R6 results capture.
20. Documentation: ADRs, diagrams, README, CHANGELOG.

---

## 20. Testing Strategy

| Level       | Tooling                         | Required for                                                      |
| ----------- | ------------------------------- | ----------------------------------------------------------------- |
| Unit        | JUnit 5 + Mockito               | every service-layer class                                         |
| Slice       | `@WebMvcTest`, `@DataJpaTest`   | controllers, repositories                                         |
| Integration | Testcontainers (Postgres+Redis) | hold flow, booking tx, outbox dispatcher, rate limiter, WS bridge |
| Contract    | OpenAPI schema validation       | all endpoints                                                     |
| Load        | k6                              | R6 measurements + rate-limit burst                                |
| Smoke E2E   | Newman                          | happy path end-to-end                                             |

JaCoCo line coverage ≥ 70 % on `backend/`. Mandatory test cases listed in v2 §20 stay unchanged.

---

## 21. Definition of Done (per requirement) — unchanged from v2 §21.

---

## 22. `docker-compose.yml` (canonical)

```yaml
name: ticketing

networks:
  internal:
    driver: bridge

volumes:
  pgdata:
  redisdata:
  prometheusdata:
  lokidata:
  tempodata:
  grafanadata:

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U ${POSTGRES_USER}']
      interval: 5s
      timeout: 3s
      retries: 10
    networks: [internal]

  redis:
    image: redis:7-alpine
    command: ['redis-server', '--notify-keyspace-events', 'Ex', '--appendonly', 'yes']
    volumes:
      - redisdata:/data
    healthcheck:
      test: ['CMD', 'redis-cli', 'ping']
      interval: 5s
      timeout: 3s
      retries: 10
    networks: [internal]

  flyway:
    image: flyway/flyway:10.18
    command:
      [
        '-url=jdbc:postgresql://postgres:5432/${POSTGRES_DB}',
        '-user=${POSTGRES_USER}',
        '-password=${POSTGRES_PASSWORD}',
        '-locations=filesystem:/flyway/sql',
        '-connectRetries=10',
        'migrate',
      ]
    volumes:
      - ./migrations:/flyway/sql:ro
    depends_on:
      postgres: { condition: service_healthy }
    networks: [internal]

  backend-1: &backend
    build: ./backend
    environment:
      SPRING_PROFILES_ACTIVE: docker
      INSTANCE_ID: backend-1
      DATABASE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      DATABASE_USERNAME: ${POSTGRES_USER}
      DATABASE_PASSWORD: ${POSTGRES_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      JWT_PRIVATE_KEY_PATH: /run/secrets/jwt_private.pem
      JWT_PUBLIC_KEY_PATH: /run/secrets/jwt_public.pem
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
      OTEL_SERVICE_NAME: tickets-backend
      JAVA_TOOL_OPTIONS: '-javaagent:/app/opentelemetry-javaagent.jar'
    volumes:
      - ./infra/jwt:/run/secrets:ro
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      flyway: { condition: service_completed_successfully }
    healthcheck:
      test: ['CMD', 'curl', '-fsS', 'http://localhost:8080/actuator/health']
      interval: 10s
      timeout: 3s
      retries: 6
      start_period: 30s
    networks: [internal]

  backend-2:
    <<: *backend
    environment:
      SPRING_PROFILES_ACTIVE: docker
      INSTANCE_ID: backend-2
      DATABASE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      DATABASE_USERNAME: ${POSTGRES_USER}
      DATABASE_PASSWORD: ${POSTGRES_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      JWT_PRIVATE_KEY_PATH: /run/secrets/jwt_private.pem
      JWT_PUBLIC_KEY_PATH: /run/secrets/jwt_public.pem
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
      OTEL_SERVICE_NAME: tickets-backend
      JAVA_TOOL_OPTIONS: '-javaagent:/app/opentelemetry-javaagent.jar'

  batch:
    build: ./batch
    environment:
      DATABASE_URL: jdbc:postgresql://postgres:5432/${POSTGRES_DB}
      DATABASE_USERNAME: ${POSTGRES_USER}
      DATABASE_PASSWORD: ${POSTGRES_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      OTEL_EXPORTER_OTLP_ENDPOINT: http://otel-collector:4318
      OTEL_SERVICE_NAME: tickets-batch
      JAVA_TOOL_OPTIONS: '-javaagent:/app/opentelemetry-javaagent.jar'
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      flyway: { condition: service_completed_successfully }
    networks: [internal]

  frontend:
    build: ./frontend
    networks: [internal]

  nginx:
    image: nginx:1.27-alpine
    ports:
      - '443:443'
      - '80:80'
    volumes:
      - ./infra/nginx/default.conf:/etc/nginx/conf.d/default.conf:ro
      - ./infra/nginx/certs:/etc/nginx/certs:ro
    depends_on:
      backend-1: { condition: service_healthy }
      backend-2: { condition: service_healthy }
    healthcheck:
      test: ['CMD', 'wget', '-qO-', 'http://localhost/health']
      interval: 10s
      timeout: 3s
      retries: 3
    networks: [internal]

  otel-collector:
    image: otel/opentelemetry-collector-contrib:0.103.0
    command: ['--config=/etc/otel/otel-collector-config.yaml']
    volumes:
      - ./infra/otel/otel-collector-config.yaml:/etc/otel/otel-collector-config.yaml:ro
    networks: [internal]

  prometheus:
    image: prom/prometheus:v2.54.0
    volumes:
      - ./infra/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prometheusdata:/prometheus
    networks: [internal]

  loki:
    image: grafana/loki:3.1.0
    command: ['-config.file=/etc/loki/loki-config.yaml']
    volumes:
      - ./infra/loki/loki-config.yaml:/etc/loki/loki-config.yaml:ro
      - lokidata:/loki
    networks: [internal]

  tempo:
    image: grafana/tempo:2.5.0
    command: ['-config.file=/etc/tempo/tempo-config.yaml']
    volumes:
      - ./infra/tempo/tempo-config.yaml:/etc/tempo/tempo-config.yaml:ro
      - tempodata:/var/tempo
    networks: [internal]

  grafana:
    image: grafana/grafana:11.1.0
    environment:
      GF_SECURITY_ADMIN_USER: admin
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
      GF_USERS_ALLOW_SIGN_UP: 'false'
    volumes:
      - ./infra/grafana/provisioning:/etc/grafana/provisioning:ro
      - ./infra/grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafanadata:/var/lib/grafana
    networks: [internal]
```

---

## 23. `application.yaml` (backend, canonical)

```yaml
server:
  port: 8080
  tomcat:
    max-http-form-post-size: 1MB
    max-swallow-size: 1MB
  servlet:
    encoding:
      charset: UTF-8
      force: true
  shutdown: graceful
  compression:
    enabled: true
    mime-types: application/json,text/html,application/xml

spring:
  application:
    name: tickets-backend
  lifecycle:
    timeout-per-shutdown-phase: 30s
  datasource:
    url: ${DATABASE_URL}
    username: ${DATABASE_USERNAME}
    password: ${DATABASE_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 10000
      max-lifetime: 1800000
  jpa:
    hibernate:
      ddl-auto: validate # Flyway owns the schema
    open-in-view: false
    properties:
      hibernate.jdbc.batch_size: 50
      hibernate.order_inserts: true
  flyway:
    enabled: false # standalone flyway service owns migration
  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      timeout: 2s
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
  servlet:
    multipart:
      max-file-size: 1MB
      max-request-size: 1MB

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      show-details: when_authorized
      probes:
        enabled: true
  metrics:
    tags:
      service: tickets-backend
      instance: ${INSTANCE_ID:backend-local}

springdoc:
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method
  api-docs:
    path: /v3/api-docs

app:
  jwt:
    private-key-path: ${JWT_PRIVATE_KEY_PATH}
    public-key-path: ${JWT_PUBLIC_KEY_PATH}
    access-token-ttl-seconds: 1800
    refresh-token-ttl-seconds: 604800
    issuer: tickets.inha.uz
  hold:
    ttl-seconds: 600
    max-per-user-per-event: 6
  ratelimit:
    capacity: 10
    refill-tokens-per-minute: 10
  outbox:
    poll-interval-ms: 200
    batch-size: 100
    max-attempts-before-poison: 10
  idempotency:
    ttl-seconds: 86400
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS:https://tickets.inha.uz,http://localhost:5173}
    allowed-methods: GET,POST,PUT,DELETE,OPTIONS
    allowed-headers: '*'
    allow-credentials: true
  cancellation:
    min-hours-before-event: 24
  payment:
    mock: true # production wires a real PSP here

logging:
  config: classpath:logback-spring.xml
```

---

## 24. `Makefile` (canonical)

```makefile
.PHONY: help up down restart logs db-up db-reset certs jwt-keys backend-build frontend-build test load-test-seat-map load-test-hold-burst clean

help:
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-22s\033[0m %s\n", $$1, $$2}'

up: certs jwt-keys ## Bring the whole stack up
	docker compose up -d --build

down: ## Stop everything
	docker compose down

restart: down up ## Restart the stack

logs: ## Tail logs
	docker compose logs -f --tail=200

db-up: ## Bring up only Postgres and apply migrations
	docker compose up -d postgres
	docker compose run --rm flyway

db-reset: ## Drop the database volume and re-bootstrap
	docker compose down -v
	docker compose up -d postgres
	docker compose run --rm flyway

certs: ## Generate self-signed TLS cert (dev only)
	mkdir -p infra/nginx/certs
	test -f infra/nginx/certs/server.crt || openssl req -x509 -nodes -newkey rsa:2048 \
		-keyout infra/nginx/certs/server.key \
		-out    infra/nginx/certs/server.crt \
		-days 365 -subj "/CN=tickets.local"

jwt-keys: ## Generate RS256 keypair for JWT
	mkdir -p infra/jwt
	test -f infra/jwt/jwt_private.pem || ( \
		openssl genpkey -algorithm RSA -out infra/jwt/jwt_private.pem -pkeyopt rsa_keygen_bits:2048 && \
		openssl rsa -in infra/jwt/jwt_private.pem -pubout -out infra/jwt/jwt_public.pem )

backend-build: ## Build the backend jar
	cd backend && ./mvnw -B -DskipTests package

frontend-build: ## Build the frontend bundle
	cd frontend && npm ci && npm run build

test: ## Run all backend tests (unit + integration via testcontainers)
	cd backend && ./mvnw -B test

load-test-seat-map: ## Run k6 against GET /events/{id}/seats and write results
	docker run --rm --network ticketing_internal -v $(PWD)/load-tests:/scripts grafana/k6 run /scripts/seat_map_load.js --out json=/scripts/results/seat_map_$(shell date +%Y%m%d_%H%M%S).json

load-test-hold-burst: ## Burst test the rate limiter
	docker run --rm --network ticketing_internal -v $(PWD)/load-tests:/scripts grafana/k6 run /scripts/hold_burst.js

clean: ## Remove build artefacts
	cd backend && ./mvnw clean
	cd frontend && rm -rf dist node_modules
```

---

## 25. Dockerfiles (canonical)

### 25.1. `backend/Dockerfile`

```dockerfile
# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package && \
    cp target/*.jar app.jar

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
# OTel agent
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.7.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /workspace/app.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

### 25.2. `batch/Dockerfile`

Identical to backend; entry point points at `BatchApplication`.

### 25.3. `frontend/Dockerfile`

```dockerfile
# ---- build stage ----
FROM node:20-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# ---- runtime stage ----
FROM nginx:1.27-alpine
COPY --from=build /workspace/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

### 25.4. `frontend/nginx.conf`

```nginx
server {
  listen 80;
  root /usr/share/nginx/html;
  index index.html;
  location / {
    try_files $uri /index.html;
  }
}
```

---

## 26. RFC 7807 Problem-Type Catalog

All `type` URIs share the base `https://tickets.inha.uz/problems/`. The constant `RfcTypes` in `/backend/src/main/java/uz/inha/tickets/common/RfcTypes.java` defines:

| Code (HTTP) | type URI suffix             | Title                       | When emitted                       |
| ----------- | --------------------------- | --------------------------- | ---------------------------------- |
| 400         | `validation-failed`         | Validation failed           | Bean validation errors             |
| 400         | `malformed-cursor`          | Malformed cursor            | Cursor cannot be decoded           |
| 401         | `unauthenticated`           | Authentication required     | Missing/expired JWT                |
| 401         | `invalid-credentials`       | Invalid credentials         | Login failure                      |
| 401         | `invalid-refresh-token`     | Invalid refresh token       | Refresh fails                      |
| 403         | `insufficient-role`         | Insufficient role           | Role check fails                   |
| 403         | `not-owner`                 | Not the resource owner      | Owner check fails                  |
| 404         | `event-not-found`           | Event not found             |                                    |
| 404         | `seat-not-found`            | Seat not found              |                                    |
| 404         | `booking-not-found`         | Booking not found           |                                    |
| 404         | `hold-not-found`            | Hold not found              | Hold expired or never existed      |
| 409         | `seat-already-held`         | Seat already held           | Hold conflict                      |
| 409         | `seat-already-booked`       | Seat already booked         | Race lost at commit                |
| 409         | `booking-already-cancelled` | Booking already cancelled   | Idempotent cancel                  |
| 409         | `cancellation-not-allowed`  | Cancellation not allowed    | Within 24h window                  |
| 409         | `event-cancelled`           | Event cancelled             | Holding/booking on cancelled event |
| 422         | `seat-cap-exceeded`         | Per-event seat cap exceeded | 6-seat cap                         |
| 422         | `payment-declined`          | Payment declined            | Mock `MOCK_PAY_DECLINED`           |
| 422         | `seats-not-same-event`      | Seats not in the same event | Mixed-event hold request           |
| 429         | `rate-limited`              | Too many requests           | R11 reject                         |
| 500         | `internal`                  | Internal server error       | Unexpected exception               |
| 503         | `redis-unavailable`         | Redis unavailable           | Write-path degradation             |

All response bodies share the structure shown in §4.3 plus a `trace_id` field populated by `ProblemDetailsAdvice`.

---

## 27. Security and Infrastructure Details

### 27.1. JWT Keys

- Algorithm: RS256.
- Generated by `make jwt-keys` (OpenSSL).
- Stored at `/infra/jwt/jwt_private.pem` and `/infra/jwt/jwt_public.pem` (gitignored).
- Mounted into backend containers as `/run/secrets/jwt_private.pem` / `jwt_public.pem`.
- Production rotation: out of scope for this academic project; documented in `/docs/adr/`.

### 27.2. Refresh Tokens

- Stored in Postgres `refresh_tokens` (SHA-256 hash of the opaque token, never the raw value).
- On `POST /auth/refresh`: server hashes the presented token, looks it up, checks `expires_at > now()` and `revoked_at IS NULL`, then issues a new access token AND rotates the refresh token (old one is marked `revoked_at = now()`).
- On `POST /auth/logout`: marks the presented refresh token as revoked.
- Refresh tokens are 32 random bytes encoded base64url (256-bit entropy).

### 27.3. CORS

Configured in `WebSecurityConfig.corsConfigurationSource()`. Allowed origins from `app.cors.allowed-origins` (comma-separated). Allowed methods: GET, POST, PUT, DELETE, OPTIONS. Credentials allowed. Max-age: 3600.

### 27.4. Request Size Limits

- Tomcat `max-http-form-post-size`: 1 MB.
- Multipart: 1 MB.
- `POST /events` is the largest body (rows config); 256 KB is sufficient — set higher (1 MB) for safety.

### 27.5. Logging

`logback-spring.xml` uses `net.logstash.logback.encoder.LogstashEncoder` with custom fields:

```xml
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LogstashEncoder">
      <customFields>{"service":"tickets-backend"}</customFields>
      <includeMdcKeyName>trace_id</includeMdcKeyName>
      <includeMdcKeyName>span_id</includeMdcKeyName>
      <includeMdcKeyName>user_id</includeMdcKeyName>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="STDOUT"/></root>
</configuration>
```

A `LogMdcFilter` extracts `trace_id` from the `traceparent` header (falling back to `X-Request-Id`) and puts it into MDC.

### 27.6. `infra/nginx/default.conf` (canonical)

```nginx
upstream backend {
    least_conn;
    server backend-1:8080 max_fails=3 fail_timeout=10s;
    server backend-2:8080 max_fails=3 fail_timeout=10s;
}

upstream backend_ws {
    ip_hash;
    server backend-1:8080;
    server backend-2:8080;
}

server {
    listen 80;
    server_name _;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name tickets.inha.uz tickets.local;

    ssl_certificate     /etc/nginx/certs/server.crt;
    ssl_certificate_key /etc/nginx/certs/server.key;

    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Content-Security-Policy "default-src 'self'; img-src 'self' data:; connect-src 'self' wss:; script-src 'self'; style-src 'self' 'unsafe-inline'" always;

    client_max_body_size 1m;

    location /health {
        return 200 "ok\n";
    }

    location ~ ^/(api|swagger-ui|v3|actuator)/ {
        proxy_pass http://backend;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Request-Id      $request_id;
    }

    location /ws/ {
        proxy_pass http://backend_ws;
        proxy_http_version 1.1;
        proxy_set_header   Upgrade       $http_upgrade;
        proxy_set_header   Connection    "upgrade";
        proxy_set_header   Host          $host;
        proxy_set_header   X-Real-IP     $remote_addr;
        proxy_read_timeout 3600s;
    }

    location / {
        proxy_pass http://frontend:80;
    }
}
```

---

## 28. QR Ticket PDF Specification

### 28.1. Endpoint

`GET /api/v1/bookings/{id}/ticket.pdf` — authorized for the booking owner only. Generated on demand (not cached on disk).

### 28.2. QR Payload

Just the booking UUID (`urn:ticket:<booking_id>`). Rationale: UUIDv4 carries ~122 bits of entropy — non-guessable. Verification on scan checks: booking exists, status = `CONFIRMED`, not previously checked-in (a `checked_in_at` column on `bookings` is left out of v1 scope but the spec leaves room for it).

### 28.3. Layout (A4 portrait, OpenPDF)

```
+------------------------------------------+
|         Mozart Gala                      |  Helvetica-Bold 24pt
|         Inha Hall · 2026-06-01 19:00     |  Helvetica       12pt
+------------------------------------------+
|                                          |
|              [ QR 200×200 ]              |  centered
|                                          |
|              Row A   Seat 1              |  Helvetica-Bold 18pt
|                 VIP · $120.00            |  Helvetica       12pt
|                                          |
|       Booking ID: 11111111-2222-...      |  Courier          9pt
|       Holder:    Alice Anderson          |  Helvetica       10pt
+------------------------------------------+
|  Please present this ticket at entrance. |  footer Helvetica 8pt
+------------------------------------------+
```

Generator: `TicketPdfService.render(Booking, User, Seat, Event) -> byte[]` using `com.lowagie.text.Document` + `com.google.zxing.qrcode.QRCodeWriter`.

---

## 29. Organizer Dashboard

### 29.1. Endpoint

`GET /api/v1/organizer/events/{id}/dashboard` — owner check: `event.organizer_id = current_user_id`.

### 29.2. Response

```json
{
  "event_id": "...",
  "event_name": "Mozart Gala",
  "event_date": "2026-06-01T19:00:00Z",
  "total_seats": 100,
  "sold": 57,
  "cancelled": 3,
  "available": 40,
  "occupancy_pct": 57.0,
  "revenue": { "gross": "6840.00", "refunded": "360.00", "net": "6480.00" },
  "by_tier": [
    { "tier": "VIP", "total": 40, "sold": 35, "revenue": "4200.00" },
    { "tier": "STANDARD", "total": 40, "sold": 20, "revenue": "1200.00" },
    { "tier": "ECONOMY", "total": 20, "sold": 2, "revenue": "50.00" }
  ],
  "daily_sales_last_30d": [{ "date": "2026-05-15", "tickets_sold": 12, "revenue": "720.00" }]
}
```

### 29.3. SQL

```sql
-- Totals & status counts
SELECT
  e.id, e.name, e.event_date, e.total_seats,
  COUNT(*) FILTER (WHERE b.booking_status='CONFIRMED') AS sold,
  COUNT(*) FILTER (WHERE b.booking_status='CANCELLED') AS cancelled,
  COALESCE(SUM(CASE WHEN b.booking_status='CONFIRMED' THEN b.amount END),0) AS gross,
  COALESCE(SUM(r.amount),0) AS refunded
FROM events e
LEFT JOIN bookings b ON b.event_id = e.id
LEFT JOIN refunds r ON r.booking_id = b.id
WHERE e.id = :eventId
GROUP BY e.id;

-- By tier
SELECT s.tier,
       COUNT(s.id) AS total,
       COUNT(b.id) FILTER (WHERE b.booking_status='CONFIRMED') AS sold,
       COALESCE(SUM(CASE WHEN b.booking_status='CONFIRMED' THEN b.amount END),0) AS revenue
FROM seats s
LEFT JOIN bookings b ON b.seat_id = s.id
WHERE s.event_id = :eventId
GROUP BY s.tier;

-- Last 30 days
SELECT sales_date, tickets_sold, revenue
FROM analytics.event_daily_sales
WHERE event_id = :eventId
  AND sales_date >= current_date - interval '30 days'
ORDER BY sales_date;
```

`available = total_seats - sold`. `net = gross - refunded`. `occupancy_pct = sold * 100.0 / total_seats`.

---

## 30. Environment Variables (canonical `.env.example`)

```env
# Postgres
POSTGRES_DB=tickets
POSTGRES_USER=tickets
POSTGRES_PASSWORD=change_me_in_prod

# Backend
DATABASE_URL=jdbc:postgresql://postgres:5432/tickets
DATABASE_USERNAME=tickets
DATABASE_PASSWORD=change_me_in_prod
REDIS_HOST=redis
REDIS_PORT=6379

# JWT
JWT_PRIVATE_KEY_PATH=/run/secrets/jwt_private.pem
JWT_PUBLIC_KEY_PATH=/run/secrets/jwt_public.pem

# CORS
CORS_ALLOWED_ORIGINS=https://tickets.inha.uz,http://localhost:5173

# Observability
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318

# Grafana
GRAFANA_ADMIN_PASSWORD=admin
```

---

## 31. R1–R13 Compliance Checklist

| Req. | Section      |
| ---- | ------------ |
| R1   | §1           |
| R2   | §2           |
| R3   | §3           |
| R4   | §4, §26      |
| R5   | §5           |
| R6   | §6           |
| R7   | §7           |
| R8   | §8, §27.6    |
| R9   | §9, §22, §25 |
| R10  | §10          |
| R11  | §11          |
| R12  | §12, §27.5   |
| R13  | §13          |
