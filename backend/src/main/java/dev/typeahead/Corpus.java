package dev.typeahead;

import dev.typeahead.core.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.TransactionDefinition;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service public class Corpus {
 record Scope(String tenant,String category,String language){}
 record Snapshot(long version,Map<Scope,ServingIndex> indexes,Map<String,Term> terms,int count,Map<String,Double> weights){}
 record Version(long version,int count,String checksum,String createdAt){}
 final AtomicReference<Snapshot> current=new AtomicReference<>(new Snapshot(0,Map.of(),Map.of(),0,Map.of()));
 volatile long confirmedAt=0;
 @Value("${index.shards:4}") int shards=4;
 @Value("${index.shard-id:-1}") int owner=-1;
 private Snapshot base;
 private Map<String,Term> delta=new HashMap<>();
 private long compactions=0;
 private record Retained(Snapshot snapshot,long at){}
 private final ConcurrentSkipListMap<Long,Retained> history=new ConcurrentSkipListMap<>();
 private final ExecutorService builder=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon(true).name("index-compactor").factory());
 private final AtomicBoolean compacting=new AtomicBoolean();
 private volatile String compactionFailure="";
 @PreDestroy void close(){builder.shutdownNow();}

 Snapshot snapshotAt(long revision){
  Snapshot snapshot=current.get();if(snapshot.version()==revision)return snapshot;
  Retained retained=history.get(revision);if(retained!=null && System.nanoTime()-retained.at()<3_000_000_000L)return retained.snapshot();
  synchronized(this){if(current.get().version()<revision)refresh();snapshot=current.get();if(snapshot.version()==revision)return snapshot;retained=history.get(revision);return retained!=null && System.nanoTime()-retained.at()<3_000_000_000L?retained.snapshot():null;}
 }

 private void publish(Snapshot next){current.set(next);if(owner>=0){long now=System.nanoTime();history.put(next.version(),new Retained(next,now));history.entrySet().removeIf(e->now-e.getValue().at()>3_000_000_000L);while(history.size()>64)history.pollFirstEntry();}}

 synchronized Map<String,Object> indexStatus(){return Map.of("baseVersion",base==null?0:base.version(),"deltaIds",delta.size(),"shards",shards,"owner",owner,"compactions",compactions,"retainedVersions",history.size(),"routingEpoch","normalized-hash-v1","compacting",compacting.get(),"compactionFailure",compactionFailure);}
 private final JdbcTemplate db;
 private final TransactionTemplate tx;private final TransactionTemplate reads;
 private final JsonMapper json;
 private static final RowMapper<Term> ROW=(r,n)->new Term(r.getString("id"),r.getString("tenant"),r.getString("text"),r.getString("type"),r.getString("category"),r.getString("language"),r.getBoolean("active"),r.getLong("popularity"),r.getDouble("boost"));
 Corpus(JdbcTemplate db,TransactionTemplate tx,JsonMapper json){this.db=db;this.tx=tx;this.json=json;this.reads=new TransactionTemplate(tx.getTransactionManager());reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
 List<Term> list(String tenant,int offset){return db.query("select * from suggestions where tenant=? order by id limit 100 offset ?",ROW,tenant,offset);}
 Term get(String tenant,String id){var rows=db.query("select * from suggestions where tenant=? and id=?",ROW,tenant,id);if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Suggestion not found");return rows.getFirst();}
 static String checksum(String payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 static String artifactChecksum(String payload,double weight){return checksum("format=2\n"+Normalizer.VERSION+"\n"+Double.toString(weight)+"\n"+payload);}
 synchronized void rebuild(){build(true);}
 @Scheduled(fixedDelay=1000) public synchronized void refresh(){
  if(base==null){build(true);return;}
  long readStarted=System.nanoTime();
  Snapshot before=current.get();
  Window window=window(before.version(),Long.MAX_VALUE);var events=window.events();long target=window.target();
  if(target==before.version()){confirmedAt=System.nanoTime();return;}
  // Only publish complete, contiguous committed revisions; concurrent newer commits are retried next poll.
  if(events==null || events.isEmpty() || events.size()>4096){build(true);return;}
  long last=((Number)events.getLast().get("revision")).longValue();
  Set<Long> committed=new HashSet<>();for(var event:events)if("COMMIT".equals(event.get("kind")))committed.add(((Number)event.get("revision")).longValue());
  if(last-before.version()!=committed.size() || events.stream().anyMatch(e->"REBUILD".equals(e.get("kind")))){build(true);return;}
  // Materialize each committed revision so a worker can answer the coordinator's exact revision.
  Map<Long,List<Map<String,Object>>> revisions=new TreeMap<>();for(var event:events)revisions.computeIfAbsent(((Number)event.get("revision")).longValue(),k->new ArrayList<>()).add(event);
  Set<String> changedIds=new HashSet<>(delta.keySet());for(var event:events)if(event.get("term_id")!=null)changedIds.add(event.get("tenant")+"\0"+event.get("term_id"));
  if(changedIds.size()>1024 || last-base.version()>=1024){build(true);return;}
  for(var revision:revisions.entrySet())applyRevision(revision.getKey(),revision.getValue());
  confirmedAt=readStarted;
  if(delta.size()>256 || last-base.version()>=256)requestCompaction();
 }
 private void applyRevision(long revision,List<Map<String,Object>> events){
  Snapshot before=current.get();Map<String,Term> updated=new HashMap<>(delta);Set<Scope> affected=new HashSet<>();
  for(var event:events){String kind=(String)event.get("kind");if(!kind.equals("UPSERT") && !kind.equals("DELETE"))continue;
   String key=event.get("tenant")+"\0"+event.get("term_id");Term old=before.terms().get(key);if(old!=null)affected.addAll(scopes(old));
   Term value=kind.equals("DELETE")?null:json.readValue((String)event.get("payload"),Term.class);updated.put(key,value);if(value!=null)affected.addAll(scopes(value));
  }
  Map<Scope,ServingIndex> indexes=new HashMap<>(before.indexes());
  for(Scope scope:affected){Map<String,Term> scoped=new HashMap<>();for(var change:updated.entrySet()){Term original=base.terms().get(change.getKey()),value=change.getValue();if(matches(original,scope) || matches(value,scope))scoped.put(change.getKey().substring(change.getKey().indexOf('\0')+1),matches(value,scope)?value:null);}
   ShardedIndex baseline=(ShardedIndex)base.indexes().get(scope);if(baseline==null)baseline=new ShardedIndex(List.of(),baselineWeight(scope),shards,owner);
   indexes.put(scope,new DeltaIndex(baseline,scoped,baseline.weight(),shards,owner));
  }
  Map<String,Term> catalog=new OverlayMap(base.terms(),updated);delta=updated;publish(new Snapshot(revision,Map.copyOf(indexes),catalog,catalog.size(),base.weights()));
 }

 private double baselineWeight(Scope scope){return base.weights().getOrDefault(scope.tenant(),1d);}
 private static boolean matches(Term t,Scope s){return t!=null && t.tenant().equals(s.tenant()) && (s.category().equals("*") || s.category().equals(t.category())) && (s.language().equals("*") || s.language().equals(t.language()));}
 private static Set<Scope> scopes(Term t){Set<Scope> result=new HashSet<>();for(String c:Set.of("*",t.category()))for(String l:Set.of("*",t.language()))result.add(new Scope(t.tenant(),c,l));return result;}

 private void lock(){db.queryForObject("select version from corpus_revision where id=1 for update",Long.class);}
 private void advance(String tenant,String action){db.update("update corpus_revision set version=version+1 where id=1");long v=db.queryForObject("select version from corpus_revision where id=1",Long.class);db.update("insert into index_update_events(revision,kind,tenant) values (?,?,?)",v,action.equals("RANKING") || action.equals("ROLLBACK")?"REBUILD":"COMMIT",tenant);db.update("insert into admin_audit(tenant,action) values (?,?)",tenant,action);}
 private record Export(long version,List<Term> terms,Map<String,Double> weights,long readAt){}
 private record Window(long target,List<Map<String,Object>> events){}
 private Window window(long from,long through){return reads.execute(status->{long target=Math.min(through,db.queryForObject("select version from corpus_revision where id=1",Long.class));return new Window(target,db.queryForList("select * from index_update_events where revision>? and revision<=? order by revision,id limit 4097",from,target));});}
 private Export export(){try(Stage stage=new Stage("compaction.export")){return reads.execute(status->{long at=System.nanoTime();long v=db.queryForObject("select version from corpus_revision where id=1",Long.class);List<Term> all=db.query("select * from suggestions order by tenant,id limit 100001",ROW);if(all.size()>100000)throw new IllegalStateException("Local corpus cap exceeded");Map<String,Double> weights=new HashMap<>();db.query("select tenant,popularity_weight from ranking_policies",(r,n)->Map.entry(r.getString(1),r.getDouble(2))).forEach(e->weights.put(e.getKey(),e.getValue()));return new Export(v,List.copyOf(all),Map.copyOf(weights),at);});}}
 // Test seam: the export transaction has already ended before construction begins.
 void afterExport(){}
 private Snapshot compile(Export export){try(Stage stage=new Stage("compaction.build")){
  afterExport();Map<Scope,List<Term>> grouped=new HashMap<>();Map<String,Term> byId=new HashMap<>();
  for(Term term:export.terms()){byId.put(term.tenant()+"\0"+term.id(),term);for(Scope scope:scopes(term))grouped.computeIfAbsent(scope,k->new ArrayList<>()).add(term);}
  Map<Scope,ServingIndex> indexes=new HashMap<>();grouped.forEach((scope,terms)->indexes.put(scope,new ShardedIndex(terms,export.weights().getOrDefault(scope.tenant(),1d),shards,owner)));
  return new Snapshot(export.version(),Map.copyOf(indexes),Map.copyOf(byId),byId.size(),export.weights());
 }}
 private void persist(Snapshot snapshot){try(Stage stage=new Stage("compaction.persist")){
  Map<String,List<Term>> tenants=new HashMap<>();for(Term term:snapshot.terms().values())tenants.computeIfAbsent(term.tenant(),k->new ArrayList<>()).add(term);
  // Serialization and artifact transactions never take the corpus revision lock.
  Map<String,String> payloads=new HashMap<>();tenants.forEach((tenant,terms)->{terms.sort(Comparator.comparing(Term::id));payloads.put(tenant,json.writeValueAsString(terms));});
  tx.executeWithoutResult(status->{payloads.forEach((tenant,payload)->{double weight=snapshot.weights().getOrDefault(tenant,1d);db.update("insert into index_snapshots(tenant,version,format,normalization,checksum,payload,term_count,popularity_weight) values (?,?,2,?,?,?,?,?) on conflict do nothing",tenant,snapshot.version(),Normalizer.VERSION,artifactChecksum(payload,weight),payload,tenants.get(tenant).size(),weight);});
   db.update("delete from index_update_events where revision <= least(?,coalesce((select revision from index_update_events order by id desc offset 99999 limit 1),-1))",snapshot.version());
   db.update("delete from index_snapshots where (tenant,version) in (select tenant,version from (select tenant,version,row_number() over (partition by tenant order by version desc) as n from index_snapshots) old where n>20)");
  });
 }}
 private void build(boolean force){Export exported=export();Snapshot next=compile(exported);persist(next);base=next;delta=new HashMap<>();compactions++;publish(next);confirmedAt=exported.readAt();}
 private void requestCompaction(){if(!compacting.compareAndSet(false,true))return;builder.submit(()->{try{Export exported=export();Snapshot candidate=compile(exported);persist(candidate);activate(candidate,exported.readAt());compactionFailure="";}catch(RuntimeException failure){compactionFailure=failure.getClass().getSimpleName();}finally{compacting.set(false);}});}
 private synchronized void activate(Snapshot candidate,long readAt){
  if(candidate.version()<=base.version())return;
  if(current.get().version()<candidate.version())refresh();
  if(candidate.version()<=base.version())return;
  long target=current.get().version();Window tail=window(candidate.version(),target);
  if(!complete(candidate.version(),target,tail.events()))return;
  Map<String,Term> changes=new HashMap<>();for(var event:tail.events()){String kind=(String)event.get("kind");if(kind.equals("UPSERT") || kind.equals("DELETE")){String key=event.get("tenant")+"\0"+event.get("term_id");changes.put(key,kind.equals("DELETE")?null:json.readValue((String)event.get("payload"),Term.class));}}
  if(changes.size()>1024)return;
  Map<Scope,ServingIndex> indexes=new HashMap<>(candidate.indexes());Set<Scope> affected=new HashSet<>();for(var e:changes.entrySet()){Term old=candidate.terms().get(e.getKey());if(old!=null)affected.addAll(scopes(old));if(e.getValue()!=null)affected.addAll(scopes(e.getValue()));}
  for(Scope scope:affected){Map<String,Term> scoped=new HashMap<>();for(var e:changes.entrySet())if(matches(candidate.terms().get(e.getKey()),scope)||matches(e.getValue(),scope))scoped.put(e.getKey().substring(e.getKey().indexOf('\0')+1),matches(e.getValue(),scope)?e.getValue():null);double weight=candidate.weights().getOrDefault(scope.tenant(),1d);ShardedIndex baseline=(ShardedIndex)candidate.indexes().get(scope);if(baseline==null)baseline=new ShardedIndex(List.of(),weight,shards,owner);indexes.put(scope,new DeltaIndex(baseline,scoped,weight,shards,owner));}
  Map<String,Term> catalog=new OverlayMap(candidate.terms(),changes);base=candidate;delta=changes;compactions++;publish(new Snapshot(target,Map.copyOf(indexes),catalog,catalog.size(),candidate.weights()));
  // Preserve the confirmation associated with the current serving revision, not export completion time.
  if(target==candidate.version())confirmedAt=Math.max(confirmedAt,readAt);
 }
 private static boolean complete(long from,long target,List<Map<String,Object>> events){if(from==target)return events.isEmpty();if(events.isEmpty() || events.size()>4096 || events.stream().anyMatch(e->"REBUILD".equals(e.get("kind"))))return false;Set<Long> markers=new HashSet<>();for(var event:events)if("COMMIT".equals(event.get("kind")))markers.add(((Number)event.get("revision")).longValue());return markers.size()==target-from;}

 private void upsert(Term t){db.update("insert into suggestions(id,tenant,text,type,category,language,active,popularity,boost) values (?,?,?,?,?,?,?,?,?) on conflict(tenant,id) do update set text=excluded.text,type=excluded.type,category=excluded.category,language=excluded.language,active=excluded.active,popularity=excluded.popularity,boost=excluded.boost",t.id(),t.tenant(),t.text(),t.type(),t.category(),t.language(),t.active(),t.popularity(),t.boost());}
 synchronized Term patch(String tenant,String id,Map<String,Object> patch){
  Term result=tx.execute(status->{lock();Term old=get(tenant,id);Map<String,Object> merged=new HashMap<>();merged.put("id",old.id());merged.put("tenant",old.tenant());merged.put("text",old.text());merged.put("type",old.type());merged.put("category",old.category());merged.put("language",old.language());merged.put("active",old.active());merged.put("popularity",old.popularity());merged.put("boost",old.boost());if(!merged.keySet().containsAll(patch.keySet()))throw new IllegalArgumentException("Unknown patch field");merged.putAll(patch);Term term=json.readValue(json.writeValueAsString(merged),Term.class);if(!id.equals(term.id()) || !tenant.equals(term.tenant()))throw new IllegalArgumentException("Immutable identity mismatch");upsert(term);advance(tenant,"PATCH");return term;});refresh();return result;
 }
 synchronized void save(Term t){importTerms(t.tenant(),List.of(t));}
 synchronized void importTerms(String tenant,List<Term> terms){
  if(terms.isEmpty() || terms.size()>1000)throw new IllegalArgumentException("Import requires 1..1000 terms");
  Set<String> ids=new HashSet<>();for(Term t:terms){if(!tenant.equals(t.tenant()) || !ids.add(t.id()))throw new IllegalArgumentException("Tenant mismatch or duplicate ID");}
  tx.executeWithoutResult(s->{lock();for(Term t:terms)upsert(t);if(db.queryForObject("select count(*) from suggestions",Long.class)>100000)throw new IllegalArgumentException("Corpus limit exceeded");advance(tenant,"UPSERT");});refresh();
 }
 synchronized void delete(String tenant,String id){tx.executeWithoutResult(s->{lock();db.update("delete from suggestions where tenant=? and id=?",tenant,id);advance(tenant,"DELETE");});refresh();}
 double weight(String tenant){var rows=db.queryForList("select popularity_weight from ranking_policies where tenant=?",Double.class,tenant);return rows.isEmpty()?1:rows.getFirst();}
 synchronized void policy(String tenant,double weight){if(!Double.isFinite(weight) || weight<0 || weight>5)throw new IllegalArgumentException("Weight must be 0..5");tx.executeWithoutResult(s->{lock();db.update("insert into ranking_policies(tenant,popularity_weight) values (?,?) on conflict(tenant) do update set popularity_weight=excluded.popularity_weight",tenant,weight);advance(tenant,"RANKING");});refresh();}
 List<Version> versions(String tenant){return db.query("select version,term_count,checksum,created_at from index_snapshots where tenant=? order by version desc limit 100",(r,n)->new Version(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4)),tenant);}
 synchronized void rollback(String tenant,long version){
  tx.executeWithoutResult(status->{
   lock();var rows=db.queryForList("select * from index_snapshots where tenant=? and version=?",tenant,version);
   if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Snapshot not found");
   var artifact=rows.getFirst();String payload=(String)artifact.get("payload");
   int format=((Number)artifact.get("format")).intValue();double weight=((Number)artifact.get("popularity_weight")).doubleValue();
   if((format!=1 && format!=2) || !Normalizer.VERSION.equals(artifact.get("normalization")) || !(format==1?checksum(payload):artifactChecksum(payload,weight)).equals(artifact.get("checksum")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Snapshot integrity or format mismatch");
   Term[] historical=json.readValue(payload,Term[].class);
   if(historical.length!=((Number)artifact.get("term_count")).intValue() || historical.length>100000)throw new IllegalArgumentException("Snapshot count mismatch");
   Set<String> seen=new HashSet<>();for(Term t:historical){if(!tenant.equals(t.tenant()) || !seen.add(t.id()))throw new IllegalArgumentException("Snapshot tenant/ID mismatch");
    // Current eligibility wins: rollback cannot recreate deleted IDs or reactivate hidden terms.
    db.update("update suggestions set text=?,popularity=?,boost=? where tenant=? and id=? and active=true",t.text(),t.popularity(),t.boost(),tenant,t.id());
   }
   db.update("insert into ranking_policies(tenant,popularity_weight) values (?,?) on conflict(tenant) do update set popularity_weight=excluded.popularity_weight",tenant,artifact.get("popularity_weight"));
   advance(tenant,"ROLLBACK");
  });refresh();
 }
}
