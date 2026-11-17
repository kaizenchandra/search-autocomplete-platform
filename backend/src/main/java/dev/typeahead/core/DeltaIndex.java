package dev.typeahead.core;
import java.util.*;
/** Immutable overlay; every changed ID suppresses its base version, including deletes and moves. */
public final class DeltaIndex implements ServingIndex {
 private final ShardedIndex base,delta;private final Set<String> suppressed;
 public DeltaIndex(ShardedIndex base,Map<String,Term> changes,double weight,int shards,int owner){this.base=base;suppressed=Set.copyOf(changes.keySet());delta=new ShardedIndex(changes.values().stream().filter(Objects::nonNull).toList(),weight,shards,owner);}
 public Comparator<Term> order(){return base.order();}public double score(Term t){return base.score(t);}
 public List<Term> prefix(String p,int k){return ShardedIndex.merge(List.of(base.prefixExcluding(p,k,suppressed),delta.prefix(p,k)),k,order());}
 public List<Term> fuzzy(String p,int k){return ShardedIndex.merge(List.of(base.fuzzyExcluding(p,k,suppressed),delta.fuzzy(p,k)),k,order());}
 public List<Term> prefixShard(int shard,String p,int k){return ShardedIndex.merge(List.of(base.prefixShardExcluding(shard,p,k,suppressed),delta.prefixShard(shard,p,k)),k,order());}
 public List<Term> fuzzyShard(int shard,String p,int k){return ShardedIndex.merge(List.of(base.fuzzyShardExcluding(shard,p,k,suppressed),delta.fuzzyShard(shard,p,k)),k,order());}
}
