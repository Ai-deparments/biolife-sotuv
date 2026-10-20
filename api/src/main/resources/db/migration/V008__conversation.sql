-- Recorded sales conversations and their AI analysis (speech-to-text → scoring). Owner: conversation.
create table conv_recording (
    id uuid primary key,
    agent_id uuid not null,
    started_at timestamptz not null,
    ended_at timestamptz not null,
    duration_sec int not null,
    date date not null,
    mime varchar(80),
    size_bytes bigint not null,
    file_path varchar(400) not null,
    status varchar(20) not null,
    attempts int not null default 0,
    lease_until timestamptz,
    error varchar(1000),
    visit_id uuid,
    outlet_name varchar(255),
    transcript text,
    analysis text,
    score int,
    promo_mentioned boolean,
    flags varchar(400),
    summary text,
    advice text,
    analysis_id uuid,
    published boolean not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null
);
create index ix_conv_recording_queue on conv_recording (status, lease_until, created_at);
create index ix_conv_recording_agent on conv_recording (agent_id, date);
create index ix_conv_recording_date on conv_recording (date, started_at desc);

-- Copies of other modules' facts this module needs (filled by events).
create table conv_visit (
    visit_id uuid primary key,
    agent_id uuid not null,
    date date not null,
    started_at timestamptz not null,
    ended_at timestamptz,
    outlet_name varchar(255)
);
create index ix_conv_visit_agent on conv_visit (agent_id, date);

create table conv_agent (
    id uuid primary key,
    name varchar(160) not null,
    region varchar(120),
    active boolean not null default true
);

-- What the analyst should know this week: the current promotion and the new product (free text, set in the panel).
create table conv_settings (
    id int primary key,
    promo_text text,
    new_product_text text,
    updated_at timestamptz
);
