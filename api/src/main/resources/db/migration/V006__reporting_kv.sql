-- Small key/value memory of reporting (last announced public address). Owner: reporting.
create table report_kv (
    key varchar(80) primary key,
    value text,
    updated_at timestamptz
);
