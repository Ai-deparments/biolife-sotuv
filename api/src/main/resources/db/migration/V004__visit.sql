-- Visit list read model for the manager panel. Owner: visit.
create table visit_record (
    visit_id uuid primary key,
    agent_id uuid not null,
    date date not null,
    started_at timestamptz not null,
    ended_at timestamptz,
    outlet_ref varchar(64),
    outlet_name varchar(255),
    distance_m int,
    within_radius boolean not null default true,
    in_plan boolean,
    photos int not null default 0,
    photos_suspect int not null default 0,
    photo_urls text,
    orders int not null default 0,
    orders_uzs bigint not null default 0,
    updated_at timestamptz not null
);
create index ix_visit_record_date on visit_record (date, started_at desc);
create index ix_visit_record_agent on visit_record (agent_id, date);

create table visit_agent (
    id uuid primary key,
    name varchar(160) not null,
    region varchar(120),
    active boolean not null default true
);

create table visit_plan_day (
    id uuid primary key,
    agent_id uuid not null,
    date date not null,
    planned int not null default 0,
    constraint uq_visit_plan_day unique (agent_id, date)
);
create index ix_visit_plan_day_date on visit_plan_day (date);
