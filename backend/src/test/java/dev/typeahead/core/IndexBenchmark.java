package dev.typeahead.core;
import org.openjdk.jmh.annotations.*;import java.util.*;import java.util.concurrent.TimeUnit;
@State(Scope.Benchmark) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
public class IndexBenchmark {
 private TrieIndex index;
 @Setup public void setup(){List<Term> terms=new ArrayList<>();for(int i=0;i<10000;i++)terms.add(new Term(""+i,"public","product "+i,"PRODUCT","shop","en",true,i,0));index=new TrieIndex(terms);}
 @Benchmark public Object hotPrefix(){return index.prefix("pro",10);}
 @Benchmark public Object rarePrefix(){return index.prefix("product 9999",10);}
 @Benchmark public Object normalization(){return Normalizer.normalize(" Ｓｐｒｉｎｇ CAFÉ ");}
}
