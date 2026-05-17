create schema if not exists analytics;

create table if not exists analytics.event_daily_sales (
    event_id          uuid          not null,
    sales_date        date          not null,
    tickets_sold      int           not null default 0,
    tickets_cancelled int           not null default 0,
    revenue           numeric(12,2) not null default 0,
    refunds_amount    numeric(12,2) not null default 0,
    primary key (event_id, sales_date)
);
