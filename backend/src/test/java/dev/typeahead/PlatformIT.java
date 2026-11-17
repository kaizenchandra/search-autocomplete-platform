package dev.typeahead;

import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in live PostgreSQL/Redis integration test: mvn -Dtest=PlatformIT test. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:55432/autocomplete}","spring.data.redis.port=56379",
 "analytics.secret=integration-only-secret-at-least-32-characters","index.internal-secret=integration-internal-shard-secret-32-characters"})
class PlatformIT {
 @LocalServerPort int port;
 @MockitoBean JwtDecoder decoder;
 @Autowired JdbcTemplate db;
 @Autowired JsonMapper json;
 @Autowired org.springframework.transaction.support.TransactionTemplate tx;
 @Autowired Corpus corpus;
 @Autowired Analytics analytics;
 final HttpClient client=HttpClient.newHttpClient();
 @BeforeEach void auth(){when(decoder.decode(anyString())).thenAnswer(invocation->{String token=invocation.getArgument(0);return Jwt.withTokenValue(token).header("alg","RS256").subject(token.equals("nosub")?null:"integration-user").claim("tenant",token.equals("other")?"test_other":"test_admin").claim("scope",token.equals("reader")?"autocomplete.read":"autocomplete.admin").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300)).build();});}
 HttpResponse<String> request(String method,String path,Object body,String token)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+path)).header("Content-Type","application/json");if(token!=null)b.header("Authorization","Bearer "+token);return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
 Map<String,Object> term(String id,String text,boolean active){return Map.of("id",id,"tenant","test_admin","text",text,"type","QUERY","category","tech","language","en","active",active,"popularity",10,"boost",0);}
 @Test void completeAdminFuzzySnapshotsAnalyticsAndIsolation()throws Exception{
  try{
   assertEquals(403,request("GET","autocomplete/recent",null,"nosub").statusCode());
   assertEquals(401,request("GET","admin/suggestions",null,null).statusCode());
   assertEquals(403,request("POST","admin/suggestions",term("it-one","Spring Alpha",true),"reader").statusCode());
   assertEquals(200,request("POST","admin/suggestions/import",List.of(term("it-one","Spring Alpha",true),term("it-two","Spring Beta",true)),"admin").statusCode());
   assertEquals(400,request("POST","admin/suggestions/import",List.of(term("duplicate","First",true),term("duplicate","Second",true)),"admin").statusCode());
   assertEquals(0,db.queryForObject("select count(*) from suggestions where tenant='test_admin' and id='duplicate'",Integer.class));
   var recovered=new Corpus(db,tx,json);recovered.refresh();assertTrue(recovered.current.get().terms().containsKey("test_admin\0it-one"));
   corpus.rebuild();
   var history=json.readTree(request("GET","admin/index/versions",null,"admin").body());long saved=history.get(0).get("version").asLong();
   assertEquals(200,request("PATCH","admin/suggestions/it-one",term("it-one","Spring Changed",true),"admin").statusCode());
   var fuzzy=json.readTree(request("GET","autocomplete?prefix=sprong&fuzzy=true",null,"admin").body());assertEquals(2,fuzzy.get("suggestions").size());assertEquals(0,fuzzy.get("suggestions").get(0).get("highlightRanges").size());
   assertEquals(0,json.readTree(request("GET","autocomplete?prefix=spr",null,"other").body()).get("suggestions").size());
   assertEquals(200,request("DELETE","admin/suggestions/it-two",null,"admin").statusCode());
   assertEquals(200,request("POST","admin/index/"+saved+"/rollback",null,"admin").statusCode());
   var restored=json.readTree(request("GET","autocomplete?prefix=spr",null,"admin").body());assertEquals(1,restored.get("suggestions").size());assertEquals("Spring Alpha",restored.get("suggestions").get(0).get("text").asText());
   assertEquals(404,request("POST","admin/index/"+saved+"/rollback",null,"other").statusCode());
   db.update("update index_snapshots set checksum=repeat('0',64) where tenant='test_admin' and version=?",saved);
   assertEquals(409,request("POST","admin/index/"+saved+"/rollback",null,"admin").statusCode());
   var submit=Map.of("intentId",UUID.randomUUID(),"kind","SUBMITTED","termId","it-one","consent",true);
   assertEquals(200,request("POST","autocomplete/events",submit,"admin").statusCode());
   assertEquals(1,json.readTree(request("GET","autocomplete/recent",null,"admin").body()).size());
   assertEquals(0,json.readTree(request("GET","autocomplete/recent",null,"other").body()).size());
   assertEquals(200,request("DELETE","autocomplete/events",null,"admin").statusCode());
   assertEquals(0,json.readTree(request("GET","autocomplete/recent",null,"admin").body()).size());
   var event=Map.of("intentId",UUID.randomUUID(),"kind","SELECTED","termId","it-one","consent",true);
   assertTrue(json.readTree(request("POST","autocomplete/events",event,"admin").body()).get("accepted").asBoolean());
   assertFalse(json.readTree(request("POST","autocomplete/events",event,"admin").body()).get("accepted").asBoolean());
   assertEquals(2,json.readTree(request("GET","admin/analytics/queries",null,"admin").body()).size());
   assertEquals(1,json.readTree(request("GET","autocomplete/trending",null,"admin").body()).get("suggestions").size());
   assertEquals(400,request("POST","autocomplete/events",event,"other").statusCode());
   assertFalse(json.readTree(request("POST","autocomplete/events",Map.of("intentId",UUID.randomUUID(),"kind","SUBMITTED","termId","it-one","consent",false),"admin").body()).get("accepted").asBoolean());
   assertEquals(200,request("PATCH","admin/suggestions/it-one",Map.of("boost",2.5),"admin").statusCode());
   assertEquals(200,request("PATCH","admin/ranking/policies/default",Map.of("popularityWeight",0),"admin").statusCode());
   assertEquals(400,request("PATCH","admin/ranking/policies/default",Map.of("popularityWeight",6),"admin").statusCode());
   assertEquals(200,request("PATCH","admin/suggestions/it-one",term("it-one","Spring Alpha",false),"admin").statusCode());
   var latest=json.readTree(request("GET","admin/index/versions",null,"admin").body()).get(0).get("version").asLong();
   assertEquals(200,request("POST","admin/index/"+latest+"/activate",null,"admin").statusCode());
   assertEquals(0,json.readTree(request("GET","autocomplete?prefix=spr&fuzzy=true",null,"admin").body()).get("suggestions").size());
  }finally{
   cleanup("test_admin");cleanup("test_other");
  }
 }
 @Test void concurrentPatchAcrossIndependentInstances()throws Exception{
  String tenant="test_concurrency";var first=new Corpus(db,tx,json);var second=new Corpus(db,tx,json);
  try{first.save(new dev.typeahead.core.Term("concurrent",tenant,"Concurrent term","QUERY","tech","en",true,0,0));
   try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
    var popularity=pool.submit(()->{for(int i=1;i<=10;i++)first.patch(tenant,"concurrent",Map.of("popularity",i));});
    var boosts=pool.submit(()->{for(int i=1;i<=10;i++)second.patch(tenant,"concurrent",Map.of("boost",i/10.0));});
    popularity.get();boosts.get();
   }
   var result=first.get(tenant,"concurrent");assertEquals(10,result.popularity());assertEquals(1,result.boost());
   assertEquals(0,first.current.get().indexes().get(new Corpus.Scope(tenant,"*","*")).prefix("missing",10).size());
  }finally{cleanup(tenant);}
 }

 @Test void retentionQuotasConcurrentDedupAndInactiveRestore()throws Exception{
  String tenant="test_limits";
  try{
   corpus.save(new dev.typeahead.core.Term("limits",tenant,"Limited approved query","QUERY","tech","en",true,0,0));corpus.rebuild();long activeVersion=corpus.current.get().version();
   corpus.patch(tenant,"limits",Map.of("active",false));corpus.policy(tenant,0);corpus.rollback(tenant,activeVersion);
   assertFalse(corpus.get(tenant,"limits").active());assertEquals(1,corpus.weight(tenant));
   for(int i=0;i<23;i++){corpus.save(new dev.typeahead.core.Term("limits",tenant,"Limited approved query","QUERY","tech","en",true,i,0));corpus.rebuild();}
   assertEquals(20,corpus.versions(tenant).size());
   UUID concurrentIntent=UUID.randomUUID();var repeated=new Analytics.Event(concurrentIntent,Analytics.Kind.SELECTED,"limits",true);
   try(var pool=java.util.concurrent.Executors.newFixedThreadPool(8)){List<java.util.concurrent.Callable<Boolean>> tasks=new ArrayList<>();for(int i=0;i<16;i++)tasks.add(()->analytics.record(tenant,"concurrent-subject",repeated));int accepted=0;for(var f:pool.invokeAll(tasks))if(f.get())accepted++;assertEquals(1,accepted);}
   Analytics.Event first=null;for(int i=0;i<100;i++){var event=new Analytics.Event(UUID.randomUUID(),Analytics.Kind.SELECTED,"limits",true);if(first==null)first=event;assertTrue(analytics.record(tenant,"quota-subject",event));}
   assertFalse(analytics.record(tenant,"quota-subject",first));
   var rejected=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->analytics.record(tenant,"quota-subject",new Analytics.Event(UUID.randomUUID(),Analytics.Kind.SELECTED,"limits",true)));assertEquals(429,rejected.getStatusCode().value());
   assertEquals(22,corpus.get(tenant,"limits").popularity());
   db.update("update analytics_daily set day=current_date-31 where tenant=?",tenant);db.update("update analytics_dedup set day=current_date-31 where tenant=?",tenant);analytics.retain();assertTrue(analytics.queries(tenant,false).isEmpty());
   long version=corpus.versions(tenant).getFirst().version();db.update("update index_snapshots set popularity_weight=2 where tenant=? and version=?",tenant,version);
   assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->corpus.rollback(tenant,version)).getStatusCode().value());
   db.update("update index_snapshots set format=999,popularity_weight=1 where tenant=? and version=?",tenant,version);
   assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->corpus.rollback(tenant,version)).getStatusCode().value());
  }finally{cleanup(tenant);}
 }


 @Test void deltaReplayCompactionAndRemoteShardProtocol()throws Exception{
  String tenant="test_delta";var other=new Corpus(db,tx,json);
  try{
   corpus.save(new dev.typeahead.core.Term("delta-one",tenant,"Delta old","QUERY","tech","en",true,1,0));corpus.rebuild();other.refresh();
   var old=corpus.current.get();long compactions=((Number)corpus.indexStatus().get("compactions")).longValue();
   corpus.patch(tenant,"delta-one",Map.of("text","Delta new","popularity",99));
   assertEquals(compactions,((Number)corpus.indexStatus().get("compactions")).longValue());assertEquals(1,corpus.indexStatus().get("deltaIds"));
   assertEquals("Delta old",old.indexes().get(new Corpus.Scope(tenant,"*","*")).prefix("delta",10).getFirst().text());
   other.refresh();assertEquals("Delta new",other.current.get().indexes().get(new Corpus.Scope(tenant,"*","*")).prefix("delta",10).getFirst().text());
   String worker="http://127.0.0.1:"+port;
   try(varGateway helper=new varGateway(new ShardGateway(json,String.join(",",Collections.nCopies(4,worker)),"integration-internal-shard-secret-32-characters"))){
    var snapshot=corpus.current.get();var scope=new Corpus.Scope(tenant,"*","*");assertEquals(snapshot.indexes().get(scope).prefix("delta",10),helper.value.lookup(snapshot,scope,"delta",10,false,4));
    try(varGateway wrongCount=new varGateway(new ShardGateway(json,String.join(",",Collections.nCopies(2,worker)),"integration-internal-shard-secret-32-characters"))){assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->wrongCount.value.lookup(snapshot,scope,"delta",10,false,2)).getStatusCode().value());}
    corpus.patch(tenant,"delta-one",Map.of("boost",1));assertEquals(503,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->helper.value.lookup(snapshot,scope,"delta",10,false,4)).getStatusCode().value());
   }
   corpus.delete(tenant,"delta-one");other.refresh();assertTrue(other.current.get().indexes().get(new Corpus.Scope(tenant,"*","*")).prefix("delta",10).isEmpty());
   // Duplicate already-applied records are ignored by the revision checkpoint.
   db.update("insert into index_update_events(revision,kind,tenant,term_id) select version,'DELETE',?,'delta-one' from corpus_revision",tenant);other.refresh();assertTrue(other.current.get().terms().get(tenant+"\0delta-one")==null);
   // Missing coverage forces a correctness-preserving full rebuild.
   db.update("update corpus_revision set version=version+1 where id=1");long before=((Number)other.indexStatus().get("compactions")).longValue();other.refresh();assertEquals(before+1,((Number)other.indexStatus().get("compactions")).longValue());
   List<dev.typeahead.core.Term> bulk=new ArrayList<>();for(int i=0;i<257;i++)bulk.add(new dev.typeahead.core.Term("bulk"+i,tenant,"Delta bulk "+i,"QUERY","tech","en",true,i,0));corpus.importTerms(tenant,bulk);awaitIdle(corpus);assertEquals(0,corpus.indexStatus().get("deltaIds"));assertEquals(20,corpus.current.get().indexes().get(new Corpus.Scope(tenant,"*","*")).prefix("delta",20).size());
  }finally{cleanup(tenant);}
 }
 static class varGateway implements AutoCloseable{final ShardGateway value;varGateway(ShardGateway value){this.value=value;}public void close(){value.close();}}


 @Test void workerHistoryAnswersIntermediateRevisionsAndCatchesUp(){String tenant="test_history";try{
  corpus.save(new dev.typeahead.core.Term("history",tenant,"History initial","QUERY","tech","en",true,1,0));corpus.rebuild();var worker=new Corpus(db,tx,json);worker.owner=0;worker.refresh();
  corpus.patch(tenant,"history",Map.of("text","History middle"));long middle=corpus.current.get().version();corpus.patch(tenant,"history",Map.of("text","History latest"));worker.refresh();
  assertEquals("History middle",worker.snapshotAt(middle).terms().get(tenant+"\0history").text());assertEquals("History latest",worker.current.get().terms().get(tenant+"\0history").text());
  corpus.patch(tenant,"history",Map.of("boost",2));long future=corpus.current.get().version();assertEquals(future,worker.snapshotAt(future).version());
  for(int i=0;i<66;i++)corpus.patch(tenant,"history",Map.of("popularity",i));worker.refresh();assertTrue(((Number)worker.indexStatus().get("retainedVersions")).intValue()<=64);assertNull(worker.snapshotAt(middle));
 }finally{cleanup(tenant);}}

 @Test void logRetentionNeverKeepsAPartialRevision(){try{db.update("insert into index_update_events(revision,kind,tenant) select 0,'COMMIT','test_log_retention' from generate_series(1,100005)");corpus.rebuild();assertEquals(0,db.queryForObject("select count(*) from index_update_events where revision=0",Long.class));}finally{db.update("delete from index_update_events where tenant='test_log_retention'");}}


 private void awaitIdle(Corpus index){long until=System.nanoTime()+15_000_000_000L;while(Boolean.TRUE.equals(index.indexStatus().get("compacting")) && System.nanoTime()<until){try{Thread.sleep(10);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}assertFalse(Boolean.TRUE.equals(index.indexStatus().get("compacting")),index.indexStatus().toString());assertEquals("",index.indexStatus().get("compactionFailure"));}
 @Test void backgroundCompactionDoesNotLockWritersAndReplaysDeletion()throws Exception{
  String tenant="test_nonblocking";var captured=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var slow=new java.util.concurrent.atomic.AtomicBoolean();
  Corpus index=new Corpus(db,tx,json){@Override void afterExport(){if(slow.get()){captured.countDown();try{assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}}};
  try{index.refresh();slow.set(true);List<dev.typeahead.core.Term> batch=new ArrayList<>();for(int i=0;i<257;i++)batch.add(new dev.typeahead.core.Term("n"+i,tenant,"Nonblocking "+i,"QUERY","tech","en",true,i,0));index.importTerms(tenant,batch);assertTrue(captured.await(5,java.util.concurrent.TimeUnit.SECONDS));
   var old=index.current.get();try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
    var writer=executor.submit(()->{corpus.delete(tenant,"n256");corpus.patch(tenant,"n255",Map.of("text","Moved entity","category","other"));});
    try{writer.get(3,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.TimeoutException timeout){release.countDown();throw timeout;} // Must finish while construction remains paused.
   }
   index.refresh();assertNull(index.current.get().terms().get(tenant+"\0n256"));slow.set(false);release.countDown();awaitIdle(index);
   assertNull(index.current.get().terms().get(tenant+"\0n256"));assertEquals("Moved entity",index.current.get().indexes().get(new Corpus.Scope(tenant,"other","en")).prefix("moved",10).getFirst().text());
   assertNotNull(old.terms().get(tenant+"\0n256"));
  }finally{slow.set(false);release.countDown();awaitIdle(index);index.close();cleanup(tenant);}
 }
 @Test void obsoleteCompactionCannotOverwriteNewRankingPolicy()throws Exception{
  String tenant="test_compaction_policy";var captured=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);var slow=new java.util.concurrent.atomic.AtomicBoolean();
  Corpus index=new Corpus(db,tx,json){@Override void afterExport(){if(slow.get()){captured.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}}};
  try{index.refresh();slow.set(true);List<dev.typeahead.core.Term> batch=new ArrayList<>();for(int i=0;i<257;i++)batch.add(new dev.typeahead.core.Term("p"+i,tenant,"Policy "+i,"QUERY","tech","en",true,i,0));index.importTerms(tenant,batch);assertTrue(captured.await(5,java.util.concurrent.TimeUnit.SECONDS));
   corpus.policy(tenant,0);slow.set(false);index.refresh();long revision=index.current.get().version();release.countDown();awaitIdle(index);
   assertTrue(index.current.get().version()>=revision);assertEquals(0,index.current.get().weights().get(tenant));assertEquals(0,index.current.get().indexes().get(new Corpus.Scope(tenant,"*","*")).score(index.current.get().terms().get(tenant+"\0p0")));
  }finally{slow.set(false);release.countDown();awaitIdle(index);index.close();cleanup(tenant);}
 }
 private void cleanup(String tenant){awaitIdle(corpus);tx.executeWithoutResult(status->{db.queryForObject("select version from corpus_revision where id=1 for update",Long.class);for(String table:List.of("recent_searches","suggestions","index_snapshots","analytics_daily","analytics_dedup","admin_audit","ranking_policies"))db.update("delete from "+table+" where tenant=?",tenant);db.update("update corpus_revision set version=version+1 where id=1");});}

}
