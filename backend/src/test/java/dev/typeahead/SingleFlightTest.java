package dev.typeahead;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class SingleFlightTest {
 @Test void coalescesConcurrentMissAndReleasesFailures()throws Exception{var flight=new SingleFlight();var calls=new AtomicInteger();var barrier=new CyclicBarrier(32);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){List<Callable<Object>> tasks=new ArrayList<>();for(int i=0;i<32;i++)tasks.add(()->{barrier.await();return flight.resolve("tenant-version-context",()->{calls.incrementAndGet();try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();}return List.of();});});for(var f:pool.invokeAll(tasks))assertEquals(List.of(),f.get());}assertEquals(1,calls.get());assertThrows(IllegalStateException.class,()->flight.resolve("failure",()->{throw new IllegalStateException();}));assertEquals(List.of(),flight.resolve("failure",List::of));}
 @Test void capsConcurrentDistinctKeys()throws Exception{var flight=new SingleFlight();var started=new CountDownLatch(128);var release=new CountDownLatch(1);try(var pool=Executors.newVirtualThreadPerTaskExecutor()){List<Future<?>> futures=new ArrayList<>();for(int i=0;i<128;i++){String key="key"+i;futures.add(pool.submit(()->flight.resolve(key,()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return List.of();})));}assertTrue(started.await(5,TimeUnit.SECONDS));try{var e=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->flight.resolve("overflow",List::of));assertEquals(503,e.getStatusCode().value());}finally{release.countDown();}for(var f:futures)f.get();}}
}
