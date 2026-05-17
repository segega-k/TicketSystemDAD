create extension if not exists pgcrypto;
create table users(id uuid primary key default gen_random_uuid(),email varchar(255) not null unique,password_hash varchar(255) not null,role varchar(32) not null,display_name varchar(255),created_at timestamptz not null default now());
create table refresh_tokens(id uuid primary key default gen_random_uuid(),token_hash varchar(128) not null unique,user_id uuid not null references users(id),expires_at timestamptz not null,revoked_at timestamptz,created_at timestamptz not null default now());
create table events(id uuid primary key default gen_random_uuid(),organizer_id uuid not null references users(id),title varchar(255) not null,description varchar(4000),starts_at timestamptz not null,status varchar(32) not null,venue_name varchar(255),city varchar(255),created_at timestamptz not null default now());
create table seats(id uuid primary key default gen_random_uuid(),event_id uuid not null references events(id),section varchar(64) not null,row_label varchar(16) not null,seat_number int not null,x int not null default 0,y int not null default 0,price_cents bigint not null,status varchar(32) not null,unique(event_id,section,row_label,seat_number));
create table bookings(id uuid primary key default gen_random_uuid(),user_id uuid not null references users(id),event_id uuid not null references events(id),idempotency_key varchar(255) not null,status varchar(32) not null,total_cents bigint not null default 0,created_at timestamptz not null default now(),cancelled_at timestamptz,refund_cents bigint not null default 0,unique(user_id,idempotency_key));
create table refunds (
    id uuid primary key default gen_random_uuid(),
    booking_id uuid not null references bookings(id),
    amount_cents bigint not null,
    reason varchar(255),
    refunded_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);
create table booking_seats(id uuid primary key default gen_random_uuid(),booking_id uuid not null references bookings(id),event_id uuid not null references events(id),seat_id uuid not null references seats(id));
create unique index ux_booking_active_seat on booking_seats(event_id,seat_id);
create table audit_events(id uuid primary key default gen_random_uuid(),actor_id uuid,action varchar(128),entity_type varchar(128),entity_id uuid,metadata varchar(4000),created_at timestamptz not null default now());
create table outbox_events(id uuid primary key default gen_random_uuid(),topic varchar(255),payload varchar(8000),created_at timestamptz not null default now(),published_at timestamptz);
insert into users(id,email,password_hash,role,display_name) values('00000000-0000-0000-0000-000000000001','organizer@example.com','$2a$10$7oB8wIvRcGNhL0mUoy.0t.9QeMALqWWH.KRtVbLt.Vh9Pg11E1tSG','ORGANIZER','Seed Organizer'),('00000000-0000-0000-0000-000000000002','customer@example.com','$2a$10$7oB8wIvRcGNhL0mUoy.0t.9QeMALqWWH.KRtVbLt.Vh9Pg11E1tSG','CUSTOMER','Seed Customer');
insert into events(id,organizer_id,title,description,starts_at,status,venue_name,city) values('10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Spring Jazz Night','Seed concert for local smoke testing',now()+interval '30 days','PUBLISHED','Inha Hall','Tashkent');
insert into seats(event_id,section,row_label,seat_number,x,y,price_cents,status) select '10000000-0000-0000-0000-000000000001','MAIN',chr(64+r),n,n,r,7500,'AVAILABLE' from generate_series(1,6) r cross join generate_series(1,12) n;
