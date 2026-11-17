package dev.typeahead.core;
import java.util.*;
import java.util.stream.Stream;
/** Read-only metadata view avoids copying the full base catalog for each bounded delta. */
public final class OverlayMap extends AbstractMap<String,Term> {
 private final Map<String,Term> base,delta;private final int size;
 public OverlayMap(Map<String,Term> base,Map<String,Term> delta){this.base=base;this.delta=Collections.unmodifiableMap(new HashMap<>(delta));int n=base.size();for(var e:delta.entrySet()){if(base.containsKey(e.getKey()))n--;if(e.getValue()!=null)n++;}size=n;}
 @Override public Term get(Object key){return delta.containsKey(key)?delta.get(key):base.get(key);}
 @Override public int size(){return size;}
 @Override public Set<Entry<String,Term>> entrySet(){return new AbstractSet<>(){public int size(){return size;}public Iterator<Entry<String,Term>> iterator(){return Stream.concat(base.entrySet().stream().filter(e->!delta.containsKey(e.getKey())),delta.entrySet().stream().filter(e->e.getValue()!=null)).map(e->Map.entry(e.getKey(),e.getValue())).iterator();}};}
}
