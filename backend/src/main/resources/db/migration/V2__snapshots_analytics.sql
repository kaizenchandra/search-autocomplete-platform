create table index_snapshots (
 tenant varchar(64) not null, version bigint not null, format integer not null,
 normalization varchar(40) not null, checksum char(64) not null, payload text not null,
 term_count integer not null, created_at timestamptz not null default now(), primary key(tenant,version));
create table analytics_dedup (
 tenant varchar(64) not null, subject_hash char(64) not null, intent_id uuid not null,
 kind varchar(24) not null, day date not null, primary key(tenant,subject_hash,intent_id,kind));
create table analytics_daily (
 tenant varchar(64) not null, term_id varchar(64) not null, kind varchar(24) not null,
 day date not null, count bigint not null default 0, primary key(tenant,term_id,kind,day));
create table ranking_policies (tenant varchar(64) primary key, popularity_weight double precision not null default 1 check(popularity_weight between 0 and 5));
create table admin_audit (id bigint generated always as identity primary key,tenant varchar(64) not null,action varchar(40) not null,created_at timestamptz not null default now());
