package dev.typeahead;
import java.time.Duration;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;
/** Optional cache population must not extend the query deadline. Saturation drops writes. */
@Service final class CacheWriter {
 private final StringRedisTemplate redis;private final MeterRegistry metrics;
 private final ThreadPoolExecutor executor;
 CacheWriter(StringRedisTemplate redis,MeterRegistry metrics){this.redis=redis;this.metrics=metrics;executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),Thread.ofPlatform().daemon(true).name("cache-writer-",0).factory(),(task,pool)->metrics.counter("autocomplete.cache.write.dropped").increment());metrics.gauge("autocomplete.cache.write.queued",executor.getQueue(),java.util.Collection::size);}
 void put(String key,String payload){executor.execute(()->{try(Stage stage=new Stage("redis.set")){redis.opsForValue().set(key,payload,Duration.ofSeconds(15));}catch(Exception failure){metrics.counter("autocomplete.cache.errors").increment();}});}
 @PreDestroy void close(){executor.shutdownNow();}
}
