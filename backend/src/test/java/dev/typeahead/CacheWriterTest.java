package dev.typeahead;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class CacheWriterTest {
 @Test @SuppressWarnings("unchecked") void optionalPopulationIsBoundedAndNeverBlocksProducer()throws Exception{
  var redis=mock(StringRedisTemplate.class);ValueOperations<String,String> values=mock(ValueOperations.class);when(redis.opsForValue()).thenReturn(values);var started=new CountDownLatch(2);var release=new CountDownLatch(1);
  doAnswer(call->{started.countDown();release.await();return null;}).when(values).set(anyString(),anyString(),any(Duration.class));
  var metrics=new SimpleMeterRegistry();var writer=new CacheWriter(redis,metrics);
  try{writer.put("first","[]");writer.put("second","[]");assertTrue(started.await(3,TimeUnit.SECONDS));for(int i=0;i<1000;i++)writer.put("key"+i,"[]");assertTrue(metrics.get("autocomplete.cache.write.dropped").counter().count()>0);assertEquals(256,metrics.get("autocomplete.cache.write.queued").gauge().value());}finally{release.countDown();writer.close();}
 }
}
