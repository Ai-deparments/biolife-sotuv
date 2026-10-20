-- Rating module read tables. Owner: rating. Fed by domain events only.
create table rating_agent (
    id uuid primary key,
    name varchar(120) not null,
    region varchar(80),
    district varchar(120),
    active boolean not null default true
);

create table rating_visit_fact (
    visit_id uuid primary key,
    agent_id uuid not null,
    date date not null,
    closed boolean not null default false,
    proofs_complete boolean not null default false,
    excused boolean not null default false,
    suspect boolean not null default false,
    blocks int not null default 0,
    score int,
    promo_mentioned boolean,
    flagged boolean not null default false,
    criteria_json text,
    order_amount_uzs bigint not null default 0
);
create index ix_rating_visit_fact_agent_date on rating_visit_fact (agent_id, date);
create index ix_rating_visit_fact_date on rating_visit_fact (date);

create table rating_weekly (
    id uuid primary key,
    agent_id uuid not null,
    week_start date not null,
    visits int not null default 0,
    closed int not null default 0,
    conversations int not null default 0,
    avg_score int not null default 0,
    proofs_rate int not null default 0,
    promo_rate int not null default 0,
    sales_uzs bigint not null default 0,
    rank int not null default 0,
    trend int not null default 0,
    streak_days int not null default 0,
    sales_bonus_uzs bigint not null default 0,
    conversation_bonus_uzs bigint not null default 0,
    bonus_reason varchar(400),
    computed_at timestamptz,
    constraint uq_rating_weekly unique (agent_id, week_start)
);
create index ix_rating_weekly_week_rank on rating_weekly (week_start, rank);

create table rating_rules (
    id int primary key,
    sales_percent_bp int not null,
    conversation_min_score int not null,
    conversation_min_proofs_rate int not null,
    conversation_bonus_uzs bigint not null,
    streak_threshold int not null,
    falling_below int not null
);

create table rating_best_case (
    id uuid primary key,
    analysis_id uuid not null unique,
    visit_id uuid not null,
    agent_id uuid not null,
    visit_date date not null,
    score int not null,
    summary text,
    published boolean not null default false,
    published_at timestamptz,
    published_by uuid,
    created_at timestamptz not null
);
create index ix_rating_best_case_agent on rating_best_case (agent_id, published, visit_date);

create table rating_mission (
    id uuid primary key,
    agent_id uuid not null,
    week_start date not null,
    criterion_code varchar(40) not null,
    current int not null,
    target int not null,
    text text not null,
    constraint uq_rating_mission unique (agent_id, week_start)
);
