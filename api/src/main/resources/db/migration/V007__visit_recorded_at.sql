-- Version of a visit row at the source, so a late older update never overwrites a newer one. Owner: visit.
alter table visit_record add column recorded_at timestamptz;
