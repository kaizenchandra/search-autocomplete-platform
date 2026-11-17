package dev.typeahead.core;
import java.util.*;
/** Build privately, then publish once. Readers never mutate nodes. */
public final class TrieIndex {
 public static final int MAX_K=20;
 public static final Comparator<Term> ORDER=Comparator.comparingDouble(Term::score).reversed().thenComparing(t->Normalizer.normalize(t.text())).thenComparing(Term::id);
 private static final class Node { final Map<Integer,Node> children=new TreeMap<>(); final Map<String,Term> best=new HashMap<>(); final List<Term> terminals=new ArrayList<>(); }
 private final Node root=new Node();
 private final Comparator<Term> order;
 private final double weight;
 private final IdentityHashMap<Term,String> normalized=new IdentityHashMap<>();
 private final IdentityHashMap<Term,Double> scores=new IdentityHashMap<>();
 private String normalized(Term term){String key=normalized.get(term);return key==null?Normalizer.normalize(term.text()):key;}
 public double score(Term term){Double cached=scores.get(term);return cached==null?Math.log1p(term.popularity())*weight+term.boost():cached;}
 public Comparator<Term> order(){return order;}
 public TrieIndex(Collection<Term> terms){this(terms,1);}
 public TrieIndex(Collection<Term> terms,double weight){if(!Double.isFinite(weight) || weight<0 || weight>5)throw new IllegalArgumentException("Weight must be 0..5");this.weight=weight;for(Term t:terms){normalized.put(t,Normalizer.normalize(t.text()));scores.put(t,Math.log1p(t.popularity())*weight+t.boost());}order=Comparator.comparingDouble(this::score).reversed().thenComparing(this::normalized).thenComparing(Term::id);build(terms);}
 private void build(Collection<Term> terms){Set<Node> terminals=new HashSet<>();for(Term t:terms)if(t.active()){String normalized=normalized(t);Node n=root;offer(n,t,normalized);for(int c:normalized.codePoints().toArray()){n=n.children.computeIfAbsent(c,k->new Node());offer(n,t,normalized);}n.terminals.add(t);terminals.add(n);}for(Node node:terminals)node.terminals.sort(order);}
 private void offer(Node n,Term t,String key){Term old=n.best.get(key);if(old==null || order.compare(t,old)<0)n.best.put(key,t);if(n.best.size()>MAX_K){Term worst=Collections.max(n.best.values(),order);n.best.remove(normalized(worst));}}
 public List<Term> prefix(String prefix,int k){if(k<1 || k>MAX_K)throw new IllegalArgumentException("limit must be 1..20");Node n=root;for(int cp:Normalizer.normalize(prefix).codePoints().toArray()){n=n.children.get(cp);if(n==null)return List.of();}return select(n.best.values(),k,order);}
 /** Precomputed fast path with best-first refill when tombstones hide cached candidates. */
 public List<Term> prefixExcluding(String prefix,int k,Set<String> excluded){
  if(excluded.isEmpty())return prefix(prefix,k);
  if(k<1 || k>MAX_K)throw new IllegalArgumentException("Invalid K");
  Node n=root;for(int cp:Normalizer.normalize(prefix).codePoints().toArray()){n=n.children.get(cp);if(n==null)return List.of();}
  List<Term> fast=n.best.values().stream().filter(t->!excluded.contains(t.id())).sorted(order).limit(k).toList();if(n.best.values().stream().noneMatch(t->excluded.contains(t.id())))return fast;
  Comparator<Node> bounds=(a,b)->order.compare(Collections.min(a.best.values(),order),Collections.min(b.best.values(),order));
  PriorityQueue<Node> pending=new PriorityQueue<>(bounds);if(!n.best.isEmpty())pending.add(n);
  Map<String,Term> candidates=new HashMap<>();long deadline=System.nanoTime()+50_000_000L;int visited=0;
  while(!pending.isEmpty()){
   if(++visited>50000 || System.nanoTime()>deadline)throw new IllegalStateException("Exact refill budget exhausted");
   if(candidates.size()==k){Term worst=Collections.max(candidates.values(),order);Term bound=Collections.min(pending.peek().best.values(),order);if(order.compare(bound,worst)>=0)break;}
   Node node=pending.poll();for(Term t:node.terminals){if(++visited>50000 || System.nanoTime()>deadline)throw new IllegalStateException("Exact refill budget exhausted");if(!excluded.contains(t.id())){candidates.merge(Normalizer.normalize(t.text()),t,(a,b)->order.compare(a,b)<0?a:b);if(candidates.size()>k){Term worst=Collections.max(candidates.values(),order);candidates.remove(Normalizer.normalize(worst.text()));}break;}}
   for(Node child:node.children.values()){if(++visited>50000 || System.nanoTime()>deadline)throw new IllegalStateException("Exact refill budget exhausted");if(!child.best.isEmpty())pending.offer(child);}
  }
  return candidates.values().stream().sorted(order).toList();
 }
 /** Bounded Levenshtein-prefix fallback; exact results always take precedence. */
 public List<Term> fuzzy(String prefix,int k){return fuzzy(prefix,k,20000,5_000_000L);}
 public List<Term> fuzzy(String prefix,int k,int visits,long nanos){return fuzzyExcluding(prefix,k,visits,nanos,Set.of());}
 public List<Term> fuzzyExcluding(String prefix,int k,int visits,long nanos,Set<String> excluded){
  if(k<1 || k>MAX_K || visits<1 || visits>20000 || nanos<1 || nanos>50_000_000L)throw new IllegalArgumentException("Invalid fuzzy budget");
  int[] query=Normalizer.normalize(prefix).codePoints().toArray();if(query.length<4 || query.length>64)return List.of();
  int[] initial=new int[query.length+1];for(int i=0;i<initial.length;i++)initial[i]=i;
  Budget budget=new Budget(visits,System.nanoTime()+nanos);Map<String,Term> candidates=new HashMap<>();
  for(var edge:root.children.entrySet()){if(budget.remaining<=0 || System.nanoTime()>budget.deadline)break;approximate(edge.getValue(),edge.getKey(),initial,query,1,budget,candidates,excluded);}
  return select(candidates.values(),k,order);
 }
 private static final class Budget{int remaining;final long deadline;Budget(int n,long d){remaining=n;deadline=d;}}
 private void approximate(Node node,int cp,int[] previous,int[] query,int depth,Budget budget,Map<String,Term> candidates,Set<String> excluded){
  if(--budget.remaining<0 || System.nanoTime()>budget.deadline)return;
  int[] row=new int[query.length+1];row[0]=depth;int min=depth;
  for(int i=1;i<row.length;i++){row[i]=Math.min(Math.min(row[i-1]+1,previous[i]+1),previous[i-1]+(query[i-1]==cp?0:1));min=Math.min(min,row[i]);}
  if(row[query.length]<=1){for(Term t:node.best.values()){if(excluded.contains(t.id()))continue;String key=Normalizer.normalize(t.text());candidates.merge(key,t,(a,b)->order.compare(a,b)<0?a:b);}if(candidates.size()>MAX_K){var keep=select(candidates.values(),MAX_K,order);candidates.clear();keep.forEach(t->candidates.put(Normalizer.normalize(t.text()),t));}return;}
  if(min<=1 && depth<query.length+1)for(var edge:node.children.entrySet()){if(budget.remaining<=0 || System.nanoTime()>budget.deadline)break;approximate(edge.getValue(),edge.getKey(),row,query,depth+1,budget,candidates,excluded);}
 }
 public static List<Term> topK(Collection<Term> candidates,int k){return select(candidates,k,ORDER);}
 private static List<Term> select(Collection<Term> candidates,int k,Comparator<Term> order){PriorityQueue<Term> heap=new PriorityQueue<>(order.reversed());for(Term t:candidates){heap.offer(t);if(heap.size()>k)heap.poll();}return heap.stream().sorted(order).toList();}
}
