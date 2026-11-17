package dev.typeahead;

import dev.typeahead.core.*;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service class ShardGateway {
 static final String EPOCH="normalized-hash-v1";
 record Request(long version,String epoch,int shard,String tenant,String category,String language,String prefix,int limit,boolean fuzzy){}
 record Reply(long version,String epoch,int shard,List<Term> terms){}
 private final List<URI> workers;private final String secret;private final JsonMapper json;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(100)).build();
 private final ExecutorService pool=Executors.newVirtualThreadPerTaskExecutor();
 private final Semaphore requests=new Semaphore(64),rpcs=new Semaphore(128);
 ShardGateway(JsonMapper json,@Value("${index.workers:}") String workers,@Value("${index.internal-secret:}") String secret){this.json=json;this.secret=secret;this.workers=workers.isBlank()?List.of():Arrays.stream(workers.split(",")).map(String::strip).map(URI::create).toList();if(this.workers.size()>16 || (!this.workers.isEmpty() && secret.length()<32))throw new IllegalArgumentException("Remote shards require <=16 workers and a 32-character secret");for(URI uri:this.workers)if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null)throw new IllegalArgumentException("Invalid worker URI");}
 boolean enabled(){return !workers.isEmpty();}
 List<Term> lookup(Corpus.Snapshot snapshot,Corpus.Scope scope,String prefix,int k,boolean fuzzy,int shards){
  if(workers.size()!=shards)throw unavailable("Routing configuration mismatch");
  if(!requests.tryAcquire())throw unavailable("Shard request capacity exceeded");
  List<Future<Reply>> futures=new ArrayList<>();long deadline=System.nanoTime()+200_000_000L;
  try{
   for(int id=0;id<workers.size();id++){final int shard=id;Request body=new Request(snapshot.version(),EPOCH+":"+shards,id,scope.tenant(),scope.category(),scope.language(),prefix,k,fuzzy);
    futures.add(pool.submit(()->{if(!rpcs.tryAcquire())throw unavailable("Shard RPC capacity exceeded");try(Stage stage=new Stage("shard.rpc")){HttpRequest request=HttpRequest.newBuilder(workers.get(shard).resolve("/internal/shards/query")).timeout(Duration.ofMillis(180)).header("Content-Type","application/json").header("X-Shard-Secret",secret).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();HttpResponse<String> reply=client.send(request,HttpResponse.BodyHandlers.ofString());if(reply.statusCode()!=200 || reply.body().length()>65536)throw unavailable("Shard unavailable or version mismatch");return json.readValue(reply.body(),Reply.class);}finally{rpcs.release();}}));
   }
   List<List<Term>> lists=new ArrayList<>();
   for(int id=0;id<futures.size();id++){long remaining=deadline-System.nanoTime();if(remaining<=0)throw unavailable("Shard deadline exceeded");Reply reply=futures.get(id).get(remaining,TimeUnit.NANOSECONDS);if(reply.version()!=snapshot.version() || !(EPOCH+":"+shards).equals(reply.epoch()) || reply.shard()!=id || reply.terms().size()>k)throw unavailable("Inconsistent shard response");
    List<Term> eligible=new ArrayList<>();for(Term t:reply.terms()){Term authoritative=snapshot.terms().get(scope.tenant()+"\0"+t.id());if(!t.equals(authoritative) || !t.active() || ShardedIndex.route(t.text(),shards)!=id || !t.tenant().equals(scope.tenant()) || (!scope.category().equals("*")&&!scope.category().equals(t.category())) || (!scope.language().equals("*")&&!scope.language().equals(t.language())) || (!fuzzy&&!Normalizer.normalize(t.text()).startsWith(prefix)))throw unavailable("Invalid shard candidate");eligible.add(t);}lists.add(eligible);
   }
   ServingIndex ranking=snapshot.indexes().get(scope);return ShardedIndex.merge(lists,k,ranking==null?TrieIndex.ORDER:ranking.order());
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable("Shard lookup interrupted");}catch(ExecutionException|TimeoutException|RejectedExecutionException e){throw unavailable("Shard lookup failed");}finally{for(Future<?> f:futures)if(!f.isDone())f.cancel(true);requests.release();}
 }
 private static ResponseStatusException unavailable(String message){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,message);}
 @PreDestroy void close(){pool.shutdownNow();}
}
