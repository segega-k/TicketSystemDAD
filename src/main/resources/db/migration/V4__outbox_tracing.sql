-- Расширение outbox_events для tracing/диагностики (Spec §5.4)
alter table outbox_events
  add column if not exists trace_id varchar(64);
alter table outbox_events
  add column if not exists attempts int not null default 0;
alter table outbox_events
  add column if not exists aggregate_type varchar(40);
alter table outbox_events
  add column if not exists aggregate_id uuid;
alter table outbox_events
  add column if not exists event_type varchar(40);

-- Аналитический view для дашборда организатора (по дням и по тарифам)
create or replace view analytics.v_event_summary as
select
    e.id                                                       as event_id,
    e.title                                                    as event_name,
    e.starts_at                                                as event_date,
    count(distinct b.id) filter (where b.status = 'CONFIRMED') as confirmed_bookings,
    count(distinct b.id) filter (where b.status = 'CANCELLED') as cancelled_bookings,
    coalesce(sum(b.total_cents) filter (where b.status = 'CONFIRMED'), 0)::numeric / 100 as gross_revenue,
    coalesce(sum(b.refund_cents) filter (where b.status = 'CANCELLED'), 0)::numeric / 100 as refunded
from events e
left join bookings b on b.event_id = e.id
group by e.id, e.title, e.starts_at;
