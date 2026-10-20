-- Reporting module read tables. Owner: reporting.
create table report_agent (
    id uuid primary key,
    name varchar(120) not null,
    region varchar(80),
    district varchar(120),
    active boolean not null default true
);

create table report_agent_day (
    id uuid primary key,
    agent_id uuid not null,
    date date not null,
    planned int not null default 0,
    started int not null default 0,
    closed int not null default 0,
    proofs_complete int not null default 0,
    excused int not null default 0,
    suspect int not null default 0,
    score_sum int not null default 0,
    score_count int not null default 0,
    promo_count int not null default 0,
    photos_total int not null default 0,
    photos_ok int not null default 0,
    blocks int not null default 0,
    sales_uzs bigint not null default 0,
    first_activity timestamptz,
    last_activity timestamptz,
    active_minutes int not null default 0,
    late_start boolean not null default false,
    early_finish boolean not null default false,
    constraint uq_report_agent_day unique (agent_id, date)
);
create index ix_report_agent_day_date on report_agent_day (date);

create table report_event_seen (
    key varchar(80) primary key
);

create table report_delivery (
    id uuid primary key,
    type varchar(20) not null,
    period_start date not null,
    period_end date not null,
    channel varchar(40) not null,
    recipient varchar(120),
    body text,
    status varchar(20) not null,
    error varchar(1000),
    sent_at timestamptz not null
);
create index ix_report_delivery_sent on report_delivery (sent_at desc);
