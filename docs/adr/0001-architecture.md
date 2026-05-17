# ADR 0001: Spring Boot modular monolith

Use a Spring Boot modular monolith with Postgres as source of truth, Redis for short-lived holds/rate limits, and an outbox table for reliable integration events. This fits the course scope while preserving clear migration paths to separate services.
