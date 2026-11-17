package dev.typeahead;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import dev.typeahead.core.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController class ShardEndpoint {
 private final Corpus corpus;private final String secret;
 ShardEndpoint(Corpus corpus,@Value("${index.internal-secret:}") String secret){this.corpus=corpus;this.secret=secret;}
 @PostMapping("/internal/shards/query") ShardGateway.Reply query(@RequestHeader(value="X-Shard-Secret",defaultValue="") String supplied,@RequestBody ShardGateway.Request request){
  if(secret.length()<32 || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(request.shard()<0 || request.shard()>=corpus.shards || (corpus.owner!=-1 && corpus.owner!=request.shard()) || !(ShardGateway.EPOCH+":"+corpus.shards).equals(request.epoch()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Routing ownership mismatch");
  if(request.tenant()==null || !request.tenant().matches("[A-Za-z0-9_-]{1,64}") || request.prefix()==null || request.prefix().length()>128 || request.category()==null || request.category().length()>64 || request.language()==null || request.language().length()>32 || request.limit()<1 || request.limit()>20)throw new IllegalArgumentException("Invalid shard request");
  var snapshot=corpus.snapshotAt(request.version());if(snapshot==null || System.nanoTime()-corpus.confirmedAt>3_000_000_000L)throw new ResponseStatusException(HttpStatus.CONFLICT,"Shard revision mismatch or stale");
  var index=snapshot.indexes().get(new Corpus.Scope(request.tenant(),request.category(),request.language()));String q=Normalizer.normalize(request.prefix());
  List<Term> terms=index==null?List.of():request.fuzzy()?index.fuzzyShard(request.shard(),q,request.limit()):index.prefixShard(request.shard(),q,request.limit());
  return new ShardGateway.Reply(snapshot.version(),ShardGateway.EPOCH+":"+corpus.shards,request.shard(),terms);
 }
}
