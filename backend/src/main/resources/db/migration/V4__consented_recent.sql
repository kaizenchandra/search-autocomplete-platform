create table recent_searches(tenant varchar(64) not null,subject_hash char(64) not null,term_id varchar(64) not null,submitted_at timestamptz not null default now(),primary key(tenant,subject_hash,term_id));
create index recent_searches_expiry on recent_searches(submitted_at);
