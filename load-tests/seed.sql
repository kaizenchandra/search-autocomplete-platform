-- Run only against the disposable local demo database. Owned synthetic namespace.
begin;
select version from corpus_revision where id=1 for update;
insert into suggestions(id,tenant,text,type,category,language,active,popularity,boost)
select 'loadbench-'||i,'public','catalog phone '||lpad(i::text,5,'0'),'PRODUCT','load','en',true,(i*7919)%100000,0
from generate_series(0,19999) as i
on conflict(tenant,id) do update set text=excluded.text,category=excluded.category,popularity=excluded.popularity,active=true;
do $$ begin if (select count(*) from suggestions)>100000 then raise exception 'Local corpus cap exceeded'; end if; end $$;
update corpus_revision set version=version+1 where id=1;
insert into index_update_events(revision,kind,tenant) select version,'COMMIT','public' from corpus_revision where id=1;
commit;
