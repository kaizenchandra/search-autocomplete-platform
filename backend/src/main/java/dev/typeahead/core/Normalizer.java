package dev.typeahead.core;
import java.util.Locale;
public final class Normalizer {
 public static final String VERSION="nfkc-root-v1";
 private static final java.util.regex.Pattern WHITESPACE=java.util.regex.Pattern.compile("\\s+");
 private Normalizer(){}
 public static String normalize(String s){if(s==null || s.length()>256)throw new IllegalArgumentException("Text exceeds 256 UTF-16 units"); return WHITESPACE.matcher(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip()).replaceAll(" ");}
 // UTF-16 offsets for React slicing; include combining marks following the match.
 public static int highlightEnd(String display,String prefix){for(int end=0;end<display.length();){end+=Character.charCount(display.codePointAt(end));if(normalize(display.substring(0,end)).startsWith(prefix)){while(end<display.length() && Character.getType(display.codePointAt(end))==Character.NON_SPACING_MARK)end+=Character.charCount(display.codePointAt(end));return end;}}return 0;}
}
