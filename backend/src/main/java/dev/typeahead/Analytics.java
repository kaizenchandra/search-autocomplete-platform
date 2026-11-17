package dev.typeahead;

import dev.typeahead.core.Term;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service class Analytics {
 enum Kind { ISSUED, SHOWN, SELECTED, SUBMITTED, SUCCEEDED, ZERO_RESULTS, ABANDONED }
 record Event(UUID intentId,Kind kind,String termId,boolean consent){}
 private final JdbcTemplate db;private final TransactionTemplate tx;private final Corpus corpus;private final String secret;
 Analytics(JdbcTemplate db,TransactionTemplate tx,Corpus corpus,@Value("${analytics.secret:}") String secret){this.db=db;this.tx=tx;this.corpus=corpus;this.secret=secret;}
 private String hash(String tenant,String subject){if(secret.length()<32)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Analytics secret not configured");try{Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(hmac.doFinal((tenant+"\0"+subject).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.GeneralSecurityException e){throw new IllegalStateException(e);}}
 boolean record(String tenant,String subject,Event event){
  if(!event.consent())return false;
  if(event.intentId()==null || event.kind()==null || event.termId()==null || event.termId().length()>64)throw new IllegalArgumentException("Invalid event");
  String id=event.termId();Term term=corpus.current.get().terms().get(tenant+"\0"+id);
  if(!id.isEmpty() && (term==null || !term.active()))throw new IllegalArgumentException("Unknown eligible suggestion");
  if((event.kind()==Kind.SELECTED || event.kind()==Kind.SHOWN || event.kind()==Kind.SUCCEEDED) && id.isEmpty())throw new IllegalArgumentException("Suggestion ID required");
  String actor=hash(tenant,subject);return Boolean.TRUE.equals(tx.execute(status->{
   // Hash-key advisory lock serializes this actor's dedup and daily quota across replicas.
   db.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,tenant+actor);
   if(db.queryForObject("select count(*) from analytics_dedup where tenant=? and subject_hash=? and intent_id=? and kind=?",Long.class,tenant,actor,event.intentId(),event.kind().name())>0)return false;
   long count=db.queryForObject("select count(*) from analytics_dedup where tenant=? and subject_hash=? and day=current_date",Long.class,tenant,actor);
   if(count>=100)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Daily analytics quota exceeded");
   int inserted=db.update("insert into analytics_dedup(tenant,subject_hash,intent_id,kind,day) values (?,?,?,?,current_date) on conflict do nothing",tenant,actor,event.intentId(),event.kind().name());
   if(inserted==0)return false;
   db.update("insert into analytics_daily(tenant,term_id,kind,day,count) values (?,?,?,current_date,1) on conflict(tenant,term_id,kind,day) do update set count=analytics_daily.count+1",tenant,id,event.kind().name());
   if(event.kind()==Kind.SUBMITTED && !id.isEmpty()){db.update("insert into recent_searches(tenant,subject_hash,term_id) values (?,?,?) on conflict(tenant,subject_hash,term_id) do update set submitted_at=now()",tenant,actor,id);db.update("delete from recent_searches where tenant=? and subject_hash=? and term_id not in (select term_id from recent_searches where tenant=? and subject_hash=? order by submitted_at desc,term_id limit 20)",tenant,actor,tenant,actor);}
   return true;
  }));
 }
 List<Map<String,Object>> queries(String tenant,boolean zeros){return db.queryForList("select term_id,kind,sum(count) as count from analytics_daily where tenant=? and day>=current_date-30 "+(zeros?"and kind='ZERO_RESULTS' ":"")+"group by term_id,kind order by count desc,term_id,kind limit 100",tenant);}
 List<Term> trending(String tenant){List<String> ids=db.query("select a.term_id from analytics_daily a join suggestions s on s.tenant=a.tenant and s.id=a.term_id where a.tenant=? and s.active and a.kind='SELECTED' and a.day>=current_date-6 group by a.term_id order by sum(a.count) desc,a.term_id limit 20",(r,n)->r.getString(1),tenant);var snapshot=corpus.current.get();return ids.stream().map(id->snapshot.terms().get(tenant+"\0"+id)).filter(Objects::nonNull).filter(Term::active).toList();}
 List<Term> recent(String tenant,String subject){String actor=hash(tenant,subject);var snapshot=corpus.current.get();return db.query("select term_id from recent_searches where tenant=? and subject_hash=? and submitted_at>=now()-interval '30 days' order by submitted_at desc,term_id limit 20",(r,n)->r.getString(1),tenant,actor).stream().map(id->snapshot.terms().get(tenant+"\0"+id)).filter(Objects::nonNull).filter(Term::active).toList();}
 void erase(String tenant,String subject){String actor=hash(tenant,subject);tx.executeWithoutResult(status->{db.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,tenant+actor);db.update("delete from recent_searches where tenant=? and subject_hash=?",tenant,actor);db.update("delete from analytics_dedup where tenant=? and subject_hash=?",tenant,actor);});}

 @Scheduled(fixedDelay=3600000) void retain(){db.update("delete from recent_searches where submitted_at<now()-interval '30 days'");db.update("delete from analytics_dedup where day<current_date-30");db.update("delete from analytics_daily where day<current_date-30");}
}
