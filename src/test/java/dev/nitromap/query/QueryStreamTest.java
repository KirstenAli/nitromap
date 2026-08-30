package dev.nitromap.query;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryStreamTest {

    @Test
    void streamsOnlyAsFarAsItsLimit() {
        CountingMap<String, String> data = new CountingMap<>();
        for (int i = 0; i < 100; i++) data.put("k" + i, "v" + i);
        QueryEngine engine = new QueryEngine(catalog(data));
        Iterable<Map<String, Object>> rows = engine.stream("SELECT v.name FROM values v LIMIT 1", Map.of());
        assertEquals(0, data.reads);
        assertEquals(1, rows.iterator().next().size());
        assertEquals(1, data.reads);
    }

    @Test
    void streamsFiltersAndProjection() {
        QueryEngine engine = new QueryEngine(catalog(new HashMap<>(Map.of("a", "Ada", "g", "Grace"))));
        var rows = engine.stream("SELECT v.name FROM values v WHERE v._key = :key", Map.of("key", "g"));
        assertEquals(Map.of("name", "Grace"), rows.iterator().next());
    }

    @Test
    void rejectsBlockingOperators() {
        QueryEngine engine = new QueryEngine(catalog(new HashMap<>()));
        assertThrows(IllegalArgumentException.class,
                () -> engine.stream("SELECT v.name FROM values v ORDER BY v.name", Map.of()));
    }

    private Catalog catalog(Map<String, String> data) {
        Schema<String> schema = Schema.<String>builder().column("name", value -> value).build();
        return new Catalog().add("values", data, schema);
    }

    private static final class CountingMap<K, V> extends HashMap<K, V> {

        private int reads;

        @Override
        public Set<Entry<K, V>> entrySet() {
            Set<Entry<K, V>> entries = super.entrySet();
            return new java.util.AbstractSet<>() {
                public int size() { return entries.size(); }
                public Iterator<Entry<K, V>> iterator() { return counting(entries.iterator()); }
            };
        }

        private Iterator<Entry<K, V>> counting(Iterator<Entry<K, V>> entries) {
            return new Iterator<>() {
                public boolean hasNext() { return entries.hasNext(); }
                public Entry<K, V> next() { reads++; return entries.next(); }
            };
        }
    }
}
