package dev.typeahead.core;
import java.util.*;
/** Epoch 1 routes normalized display keys, colocating duplicates to make shard K sufficient. */
public final class ShardedIndex implements ServingIndex {
 private final List<TrieIndex> shards;private final int count,owner;private final double weight;private final TrieIndex ranking;
 public ShardedIndex(Collection<Term> terms,double weight,int count,int owner){
  if(count<1 || count>16 || owner < -2 || owner>=count)throw new IllegalArgumentException("Invalid shard ownership");this.count=count;this.owner=owner;this.weight=weight;
  ranking=new TrieIndex(List.of(),weight);List<List<Term>> partitions=new ArrayList<>();for(int i=0;i<count;i++)partitions.add(new ArrayList<>());
  if(owner!=-2)for(Term t:terms){int shard=route(t.text(),count);if(owner==-1 || owner==shard)partitions.get(shard).add(t);}
  List<TrieIndex> built=new ArrayList<>();for(var p:partitions)built.add(new TrieIndex(p,weight));shards=List.copyOf(built);
 }
 public static int route(String text,int count){return Math.floorMod(Normalizer.normalize(text).hashCode(),count);}
 public int count(){return count;}public double weight(){return weight;}
 public Comparator<Term> order(){return ranking.order();}public double score(Term t){return ranking.score(t);}
 public List<Term> prefix(String p,int k){return prefixExcluding(p,k,Set.of());}
 public List<Term> prefixExcluding(String p,int k,Set<String> excluded){List<List<Term>> lists=new ArrayList<>();for(int i=0;i<count;i++)lists.add(prefixShardExcluding(i,p,k,excluded));return merge(lists,k,order());}
 public List<Term> prefixShard(int shard,String p,int k){return prefixShardExcluding(shard,p,k,Set.of());}
 public List<Term> prefixShardExcluding(int shard,String p,int k,Set<String> excluded){return shards.get(shard).prefixExcluding(p,k,excluded);}
 public List<Term> fuzzy(String p,int k){return fuzzyExcluding(p,k,Set.of());}
 public List<Term> fuzzyExcluding(String p,int k,Set<String> excluded){List<List<Term>> lists=new ArrayList<>();for(int i=0;i<count;i++)lists.add(fuzzyShardExcluding(i,p,k,excluded));return merge(lists,k,order());}
 public List<Term> fuzzyShard(int shard,String p,int k){return fuzzyShardExcluding(shard,p,k,Set.of());}
 public List<Term> fuzzyShardExcluding(int shard,String p,int k,Set<String> excluded){return shards.get(shard).fuzzyExcluding(p,k,Math.max(1,20000/count),Math.max(1,5_000_000L/count),excluded);}
 public static List<Term> merge(Collection<? extends Collection<Term>> lists,int k,Comparator<Term> order){
  if(k<1 || k>TrieIndex.MAX_K)throw new IllegalArgumentException("Invalid K");
  if(lists.size()>32 || lists.stream().anyMatch(list->list.size()>40))throw new IllegalArgumentException("Unbounded shard response");
  Map<String,Term> best=new HashMap<>();for(var list:lists)for(Term t:list)best.merge(Normalizer.normalize(t.text()),t,(a,b)->order.compare(a,b)<0?a:b);
  PriorityQueue<Term> heap=new PriorityQueue<>(order.reversed());for(Term t:best.values()){heap.offer(t);if(heap.size()>k)heap.poll();}return heap.stream().sorted(order).toList();
 }
}
