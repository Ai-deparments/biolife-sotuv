-- AI check of a visit's shelf photo: is it a shelf, how many BioLife facings, verdict against the standard. Owner: visit.
-- status: leased (taken by the worker, or waiting for a retry until lease_until) | done | failed (gave up).
create table visit_photo_check (
    visit_id uuid primary key,
    agent_id uuid not null,
    date date not null,
    status varchar(16) not null,
    attempts int not null default 0,
    lease_until timestamptz,
    error varchar(1000),
    kind varchar(16),
    clear boolean,
    facings int,
    expected int,
    verdict varchar(16),
    brands varchar(400),
    competitors varchar(200),
    note varchar(400),
    photos_seen int,
    model varchar(80),
    checked_at timestamptz,
    updated_at timestamptz not null
);
create index ix_visit_photo_check_day on visit_photo_check (date, agent_id, status);

-- The shelf standard the photo AI checks against (single row, edited in the panel).
create table visit_photo_settings (
    id int primary key,
    expected_facings int not null default 1,
    standard_text text,
    updated_at timestamptz
);
