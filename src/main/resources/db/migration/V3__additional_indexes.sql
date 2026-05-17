-- pg_trgm для fuzzy-поиска по названиям событий
create extension if not exists pg_trgm;

-- GIN-индекс по названию события для ILIKE '%q%' (UC1)
create index if not exists idx_events_title_trgm on events using gin (title gin_trgm_ops);

-- Частичный индекс по дате будущих SCHEDULED/PUBLISHED событий
create index if not exists idx_events_starts_at on events (starts_at)
  where starts_at >= now() and status in ('PUBLISHED','SCHEDULED');

-- Карта мест (seat-map чтение): фильтр по event+status
create index if not exists idx_seats_event_status on seats (event_id, status);

-- Букинги пользователя в хронологии (история заказов)
create index if not exists idx_bookings_user_created on bookings (user_id, created_at desc);

-- Букинги по событию (для дашборда)
create index if not exists idx_bookings_event on bookings (event_id);

-- Подтверждённые места: hard-guarantee против двойного бронирования при отмене и пересоздании
create unique index if not exists uniq_booking_seat_confirmed
  on booking_seats (event_id, seat_id);

-- Outbox dispatcher: partial index для O(batch) выборки неотправленных
create index if not exists idx_outbox_undispatched
  on outbox_events (created_at)
  where published_at is null;

-- Audit: lookup по сущности
create index if not exists idx_audit_entity on audit_events (entity_type, entity_id, created_at desc);

-- Refresh tokens активного пользователя
create index if not exists idx_refresh_tokens_user on refresh_tokens (user_id)
  where revoked_at is null;
