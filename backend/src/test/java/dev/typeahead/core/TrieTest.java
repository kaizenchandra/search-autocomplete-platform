package dev.typeahead.core;
import org.junit.jupiter.api.Test;import java.util.*;import java.util.concurrent.*;import static org.junit.jupiter.api.Assertions.*;
class TrieTest {
 Term term(int i,String text){return new Term(""+i,"public",text,"QUERY","tech","en",true,i,0);}
 @Test void normalization(){assertEquals("café",Normalizer.normalize(" ＣＡＦÉ "));assertEquals(Normalizer.normalize("A\u0301"),Normalizer.normalize("Á"));assertEquals(2,Normalizer.highlightEnd("A\u0301bc","á"));}
 @Test void referenceAndDedup(){Random r=new Random(42);List<Term> terms=new ArrayList<>();for(int i=0;i<3000;i++)terms.add(term(i,"term "+r.nextInt(2000)));TrieIndex index=new TrieIndex(terms);for(String p:List.of("","t","term 1","term 19","missing")){Map<String,Term> unique=new HashMap<>();for(Term t:terms)if(Normalizer.normalize(t.text()).startsWith(p))unique.merge(Normalizer.normalize(t.text()),t,(a,b)->TrieIndex.ORDER.compare(a,b)<0?a:b);assertEquals(unique.values().stream().sorted(TrieIndex.ORDER).limit(10).toList(),index.prefix(p,10));}}
 @Test void deletionByRebuild(){var t=term(1,"apple");assertEquals(1,new TrieIndex(List.of(t)).prefix("app",10).size());assertTrue(new TrieIndex(List.of()).prefix("app",10).isEmpty());}
 @Test void concurrentReads()throws Exception{var index=new TrieIndex(List.of(term(1,"Spring")));try(var pool=Executors.newVirtualThreadPerTaskExecutor()){List<Callable<Void>> work=new ArrayList<>();for(int i=0;i<5000;i++)work.add(()->{assertEquals("Spring",index.prefix("spr",10).getFirst().text());return null;});for(var f:pool.invokeAll(work))f.get();}}
 @Test void supplementary(){assertEquals("😀 hello",new TrieIndex(List.of(term(1,"😀 hello"))).prefix("😀",1).getFirst().text());assertThrows(IllegalArgumentException.class,()->new TrieIndex(List.of()).prefix("a",21));}
}
