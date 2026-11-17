package dev.typeahead;
import jdk.jfr.*;
@Name("autocomplete.Stage") @Label("Autocomplete stage") @Category("Autocomplete") @Threshold("1 ms")
final class Stage extends Event implements AutoCloseable {
 @Label("Stage") String stage;
 Stage(String stage){this.stage=stage;begin();}
 @Override public void close(){end();commit();}
}
