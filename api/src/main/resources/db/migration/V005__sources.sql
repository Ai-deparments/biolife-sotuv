-- Freshness of upstream data sources. Owner: sources.
create table source_status (
    source varchar(40) primary key,
    title varchar(120),
    synced_at timestamptz,
    ok boolean not null default true,
    detail varchar(500),
    received_at timestamptz not null
);
