package dev.typeahead;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
/** Bounded coalescing by already-authorized version/context key. No completed-result retention. */
final class SingleFlight {
 private final ConcurrentHashMap<String,CompletableFuture<List<dev.typeahead.core.Term>>> pending=new ConcurrentHashMap<>();
 private final Semaphore slots=new Semaphore(128);
 List<dev.typeahead.core.Term> resolve(String key,Supplier<List<dev.typeahead.core.Term>> loader){
  var active=pending.get(key);if(active!=null)return await(active);
  if(!slots.tryAcquire())throw unavailable();
  var created=new CompletableFuture<List<dev.typeahead.core.Term>>();active=pending.putIfAbsent(key,created);
  if(active!=null){slots.release();return await(active);}
  try{var value=List.copyOf(loader.get());created.complete(value);return value;}catch(RuntimeException e){created.completeExceptionally(e);throw e;}finally{pending.remove(key,created);slots.release();}
 }
 private List<dev.typeahead.core.Term> await(CompletableFuture<List<dev.typeahead.core.Term>> future){try{return future.get(250,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw unavailable();}catch(TimeoutException e){throw unavailable();}catch(ExecutionException e){if(e.getCause() instanceof RuntimeException runtime)throw runtime;throw unavailable();}}
 private ResponseStatusException unavailable(){return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Candidate loading capacity or deadline exceeded");}
}
