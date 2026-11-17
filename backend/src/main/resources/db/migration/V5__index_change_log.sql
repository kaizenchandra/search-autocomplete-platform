create table index_update_events (
 id bigint generated always as identity primary key,
 revision bigint not null, kind varchar(16) not null,
 tenant varchar(64), term_id varchar(64), payload text,
 created_at timestamptz not null default now());
create index index_events_revision on index_update_events(revision,id);
create function capture_suggestion_change() returns trigger language plpgsql as $$
declare next_revision bigint;
begin
 select version+1 into next_revision from corpus_revision where id=1;
 if TG_OP='DELETE' then
  insert into index_update_events(revision,kind,tenant,term_id) values(next_revision,'DELETE',OLD.tenant,OLD.id);
  return OLD;
 else
  insert into index_update_events(revision,kind,tenant,term_id,payload) values(next_revision,'UPSERT',NEW.tenant,NEW.id,
    json_build_object('id',NEW.id,'tenant',NEW.tenant,'text',NEW.text,'type',NEW.type,'category',NEW.category,'language',NEW.language,'active',NEW.active,'popularity',NEW.popularity,'boost',NEW.boost)::text);
  return NEW;
 end if;
end $$;
create trigger suggestion_change after insert or update or delete on suggestions for each row execute function capture_suggestion_change();
