package dev.typeahead.core;
import java.util.*;
public interface ServingIndex {
 List<Term> prefix(String prefix,int k);
 List<Term> fuzzy(String prefix,int k);
 List<Term> prefixShard(int shard,String prefix,int k);
 List<Term> fuzzyShard(int shard,String prefix,int k);
 Comparator<Term> order();
 double score(Term term);
}
