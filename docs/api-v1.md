# API v1 contract

Base URL: `http://localhost:8080/api/v1`

Responses are JSON unless noted. Authenticated endpoints require `Authorization: Bearer <accessToken>`. Validation and domain errors use RFC7807 ProblemDetail and include a `trace_id` property.

The implementation still exposes selected legacy `/api/*` aliases for older clients. New docs, smoke tests, and frontend code should use `/api/v1`.

## Auth

| Method | Path             | Auth          | Request                                                                                        | Response                                                  |
| ------ | ---------------- | ------------- | ---------------------------------------------------------------------------------------------- | --------------------------------------------------------- |
| `POST` | `/auth/register` | public        | `{ "email", "password", "role", "displayName" }` (`display_name`/`full_name` aliases accepted) | `201` with access/refresh token payload and user details. |
| `POST` | `/auth/login`    | public        | `{ "email", "password" }`                                                                      | Access/refresh token payload and user details.            |
| `POST` | `/auth/refresh`  | public        | `{ "refreshToken" }` (`refresh_token` alias accepted)                                          | New access/refresh token payload.                         |
| `POST` | `/auth/logout`   | refresh token | Header `X-Refresh-Token: <token>` or body `{ "refreshToken" }`                                 | `204 No Content`.                                         |

Demo users: `organizer@example.com` and `customer@example.com`, password `password123`.

## Events

| Method | Path                          | Auth            | Notes                                                                                                                                                                                                   |
| ------ | ----------------------------- | --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET`  | `/events?q=&cursor=&limit=20` | public          | Search events with cursor pagination; `limit` is capped at 100.                                                                                                                                         |
| `GET`  | `/events/{id}`                | public          | Event detail.                                                                                                                                                                                           |
| `GET`  | `/events/{id}/seats`          | public          | SPEC-shaped grouped seat map with `AVAILABLE`, `HELD`, and `BOOKED` seat statuses. Legacy `/events/{id}/seat-map` also exists.                                                                          |
| `POST` | `/events`                     | organizer/admin | Create event and generated seat map. Body includes `title`/`name`, `description`, `startsAt`/`event_date`, `venueName`/`venue_name`, and either `rows` or legacy `rowCount`/`seatsPerRow`/`priceCents`. |

## Holds

| Method   | Path                        | Auth     | Request                                                                      | Notes                                                                                                             |
| -------- | --------------------------- | -------- | ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `POST`   | `/seats/hold`               | customer | `{ "event_id", "seat_ids": ["..."] }` (`eventId`/`seatIds` aliases accepted) | Creates a temporary hold; enforces 1-6 seats per request, adjacency, user/event caps, Redis TTL, and rate limits. |
| `DELETE` | `/seats/hold/{holdGroupId}` | customer | none                                                                         | Releases a hold owned by the current user; returns `204`.                                                         |
| `DELETE` | `/seats/hold`               | customer | `{ "hold_token" }`                                                           | Body-based release alias.                                                                                         |
| `POST`   | `/seats/hold/release`       | customer | `{ "hold_token" }`                                                           | Body-based release alias.                                                                                         |

## Bookings

| Method | Path                                  | Auth     | Request/Response                                                                                                                           |
| ------ | ------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `POST` | `/bookings`                           | customer | Confirm a hold. Body `{ "hold_group_id", "hold_token", "payment_token" }`; header `Idempotency-Key` is required for v1-compatible retries. |
| `GET`  | `/users/me/bookings?cursor=&limit=20` | customer | Current user's booking history as `{ "items": [...], "next_cursor": "" }`.                                                                 |
| `GET`  | `/bookings/{id}`                      | customer | Booking detail if owned or authorized.                                                                                                     |
| `POST` | `/bookings/{id}/cancel`               | customer | Cancel/refund subject to service rules.                                                                                                    |
| `GET`  | `/bookings/{id}/ticket.pdf`           | customer | Returns `application/pdf` bytes for the ticket.                                                                                            |

## Organizer analytics

| Method | Path                                    | Auth            | Notes                                                  |
| ------ | --------------------------------------- | --------------- | ------------------------------------------------------ |
| `GET`  | `/organizer/events/{eventId}/dashboard` | organizer/admin | Booking/revenue aggregation for the organizer's event. |
| `GET`  | `/organizer/events/{eventId}/analytics` | organizer/admin | Alias returning the same event analytics shape.        |
| `GET`  | `/organizer/dashboard`                  | organizer/admin | Overall counts, tickets sold, and revenue summary.     |

## Realtime and operations

- WebSocket/STOMP endpoint: `/ws`
- Seat updates topic: `/topic/events/{eventId}/seats`
- Swagger UI: `/swagger-ui.html`
- OpenAPI JSON: `/v3/api-docs`
- Health: `/actuator/health`
- Prometheus metrics: `/actuator/prometheus`
