alter table index_snapshots add column popularity_weight double precision not null default 1 check(popularity_weight between 0 and 5);
