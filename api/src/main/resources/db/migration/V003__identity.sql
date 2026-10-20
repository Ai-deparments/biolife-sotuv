-- Identity module. Owner: identity.
create table identity_user (
    id uuid primary key,
    username varchar(64) not null unique,
    password_hash varchar(100) not null,
    name varchar(120) not null,
    role varchar(20) not null,
    active boolean not null default true,
    created_at timestamptz not null,
    last_login_at timestamptz
);
