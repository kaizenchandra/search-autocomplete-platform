package dev.typeahead.core;
import java.util.Set;
public record Term(String id, String tenant, String text, String type, String category, String language, boolean active, long popularity, double boost) {
 public Term { if(id==null || !id.matches("[a-zA-Z0-9_-]{1,64}") || tenant==null || !tenant.matches("[a-zA-Z0-9_-]{1,64}") || type==null || !Set.of("QUERY","PRODUCT","CITY","RESTAURANT","VIDEO","USER","DOCUMENT","BRAND","CATEGORY").contains(type) || category==null || category.isBlank() || category.length()>64 || category.equals("*") || language==null || language.isBlank() || language.length()>32 || language.equals("*") || text==null || text.isBlank() || text.length()>256 || Normalizer.normalize(text).isEmpty() || popularity<0 || !Double.isFinite(boost) || boost<0 || boost>10) throw new IllegalArgumentException("Invalid term"); }
 public double score(){ return Math.log1p(popularity)+boost; }
}
