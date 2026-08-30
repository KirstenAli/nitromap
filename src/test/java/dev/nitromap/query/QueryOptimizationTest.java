package dev.nitromap.query;

import dev.nitromap.NitroMap;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryOptimizationTest {

    @Test
    void usesDirectKeyLookupsInEitherDirection() {
        ScanCountingMap<String, Person> people = people();
        QueryEngine engine = engine(people);
        people.resetScans();
        assertEquals("Bob", name(engine.query(keyQuery(), Map.of("key", "c2"))));
        assertEquals("Bob", name(engine.query(reverseKeyQuery(), Map.of("key", "c2"))));
        assertEquals("Bob", name(engine.query(keyAndCityQuery(), Map.of("key", "c2"))));
        assertEquals("Bob", name(engine.query(cityAndKeyQuery(), Map.of("key", "c2"))));
        assertEquals(0, engine.query(keyQuery(), Map.of("key", "missing")).size());
        assertEquals(0, people.scans());
    }

    @Test
    void preservesNumericKeyEquality() {
        NitroMap<Integer, String> rows = new NitroMap<>(Map.of(1, "one"));
        Schema<String> schema = Schema.<String>builder().column("value", value -> value).build();
        QueryResult result = new QueryEngine(new Catalog().add("rows", rows, schema))
                .query("SELECT r.value FROM rows r WHERE r._key = 1");
        assertEquals("one", result.rows().get(0).get("value"));
    }

    @Test
    void stopsSimpleQueriesAtTheirLimit() {
        AtomicInteger reads = new AtomicInteger();
        QueryEngine engine = strings(reads, 100);
        assertEquals(3, engine.query("SELECT r.value FROM rows r LIMIT 3").size());
        assertEquals(3, reads.get());
    }

    @Test
    void skipsRowsForZeroLimit() {
        AtomicInteger reads = new AtomicInteger();
        QueryEngine engine = strings(reads, 100);
        assertEquals(0, engine.query("SELECT r.value FROM rows r LIMIT 0").size());
        assertEquals(0, reads.get());
    }

    @Test
    void retainsFullInputForOrderedLimits() {
        AtomicInteger reads = new AtomicInteger();
        QueryEngine engine = strings(reads, 100);
        assertEquals(3, engine.query(orderedLimit()).size());
        assertEquals(100, reads.get());
    }

    @Test
    void usesSecondaryIndexesWithoutScanning() {
        ScanCountingMap<String, Person> people = people();
        QueryEngine engine = indexed(people, "city");
        people.resetScans();
        assertEquals(List.of("Alice", "Cara"), names(engine, "city", "London"));
        assertEquals(0, people.scans());
    }

    @Test
    void keepsOrPredicatesOnTheSafeScanPath() {
        ScanCountingMap<String, Person> people = people();
        QueryEngine engine = indexed(people, "city");
        people.resetScans();
        assertEquals(List.of("Alice", "Bob", "Cara"), orNames(engine));
        assertEquals(1, people.scans());
    }

    @Test
    void keepsSecondaryIndexesCurrent() {
        ScanCountingMap<String, Person> people = people();
        QueryEngine engine = indexed(people, "city");
        mutateIndexedPeople(people);
        assertEquals(List.of("Eve"), names(engine, "city", "London"));
        assertEquals(List.of("Alice"), names(engine, "city", "Rome"));
    }

    private void mutateIndexedPeople(ScanCountingMap<String, Person> people) {
        people.putAll(Map.of("c5", new Person("Eve", "London", 50, null)));
        people.put("c1", new Person("Alice", "Rome", 10, null));
        people.remove("c3");
        people.remove("c4", people.get("c4"));
        people.put("c6", new Person("Fay", "London", 60, null));
        people.removeAll(Set.of("c6"));
    }

    @Test
    void keepsIndexesCorrectUnderConcurrentWrites() {
        ScanCountingMap<String, Person> people = people();
        QueryEngine engine = indexed(people, "city");
        IntStream.range(0, 1_000).parallel().forEach(index ->
                people.put("shared", new Person("Shared", "city-" + index, index, null)));
        String city = people.get("shared").city();
        assertEquals(List.of("Shared"), names(engine, "city", city));
    }

    @Test
    void indexesNumericAndNullValues() {
        ScanCountingMap<String, Person> people = people();
        Catalog catalog = catalog(people).index("people", "score")
                .index("people", "nickname");
        QueryEngine engine = new QueryEngine(catalog);
        assertEquals(List.of("Bob"), names(engine, "score", 20));
        assertEquals(List.of("Alice", "Bob", "Cara"), names(engine, "nickname", null));
    }

    @Test
    void usesMaintainedIndexesForJoins() {
        ScanCountingMap<String, Order> orders = orders();
        QueryEngine engine = joinEngine(orders);
        orders.resetScans();
        assertEquals(List.of(30, 120, 200), totals(engine.query(joinQuery())));
        assertEquals(0, orders.scans());
    }

    @Test
    void validatesSecondaryIndexConfiguration() {
        Schema<String> schema = Schema.<String>builder().column("value", value -> value).build();
        Catalog ordinary = new Catalog().add("rows", new HashMap<>(), schema);
        Catalog indexed = new Catalog().add("rows", new NitroMap<String, String>(), schema);
        assertThrows(IllegalArgumentException.class, () -> ordinary.index("rows", "value"));
        assertThrows(IllegalArgumentException.class, () -> indexed.index("rows", "missing"));
        assertThrows(IllegalArgumentException.class, () -> indexed.index("rows", "_key"));
    }

    private QueryEngine indexed(ScanCountingMap<String, Person> people, String column) {
        return new QueryEngine(catalog(people).index("people", column));
    }

    private QueryEngine engine(ScanCountingMap<String, Person> people) {
        return new QueryEngine(catalog(people));
    }

    private Catalog catalog(Map<String, Person> people) {
        return new Catalog().add("people", people, personSchema());
    }

    private Schema<Person> personSchema() {
        return Schema.<Person>builder().column("name", Person::name)
                .column("city", Person::city).column("score", Person::score)
                .column("nickname", Person::nickname).build();
    }

    private ScanCountingMap<String, Person> people() {
        ScanCountingMap<String, Person> people = new ScanCountingMap<>();
        people.putAll(Map.of(
                "c1", new Person("Alice", "London", 10, null),
                "c2", new Person("Bob", "Paris", 20, null),
                "c3", new Person("Cara", "London", 30, null),
                "c4", new Person("Dan", "Rome", 40, "D'Angelo")));
        return people;
    }

    private QueryEngine strings(AtomicInteger reads, int count) {
        NitroMap<Integer, String> rows = new NitroMap<>();
        for (int key = 0; key < count; key++) rows.put(key, "value-" + key);
        Schema<String> schema = Schema.<String>builder()
                .column("value", value -> read(reads, value)).build();
        return new QueryEngine(new Catalog().add("rows", rows, schema));
    }

    private String read(AtomicInteger reads, String value) {
        reads.incrementAndGet();
        return value;
    }

    private QueryEngine joinEngine(ScanCountingMap<String, Order> orders) {
        NitroMap<String, Person> people = people();
        Catalog catalog = catalog(people).add("orders", orders, orderSchema())
                .index("orders", "customerId");
        return new QueryEngine(catalog);
    }

    private Schema<Order> orderSchema() {
        return Schema.<Order>builder().column("customerId", Order::customerId)
                .column("total", Order::total).build();
    }

    private ScanCountingMap<String, Order> orders() {
        ScanCountingMap<String, Order> orders = new ScanCountingMap<>();
        orders.putAll(Map.of("o1", new Order("c1", 120),
                "o2", new Order("c1", 30), "o3", new Order("c2", 200)));
        return orders;
    }

    private List<Object> names(QueryEngine engine, String column, Object value) {
        String sql = "SELECT p.name FROM people p WHERE p." + column
                + " = :value ORDER BY p.name";
        return engine.query(sql, singleton("value", value)).rows().stream()
                .map(row -> row.get("name")).toList();
    }

    private List<Object> orNames(QueryEngine engine) {
        String sql = "SELECT p.name FROM people p WHERE p.city = 'London' "
                + "OR p.name = 'Bob' ORDER BY p.name";
        return engine.query(sql).rows().stream().map(row -> row.get("name")).toList();
    }

    private Map<String, Object> singleton(String name, Object value) {
        Map<String, Object> parameter = new HashMap<>();
        parameter.put(name, value);
        return parameter;
    }

    private String name(QueryResult result) {
        return String.valueOf(result.rows().get(0).get("name"));
    }

    private List<Object> totals(QueryResult result) {
        return result.rows().stream().map(row -> row.get("total")).toList();
    }

    private String keyQuery() {
        return "SELECT p.name FROM people p WHERE p._key = :key";
    }

    private String reverseKeyQuery() {
        return "SELECT p.name FROM people p WHERE :key = p._key";
    }

    private String keyAndCityQuery() {
        return "SELECT p.name FROM people p WHERE p._key = :key AND p.city = 'Paris'";
    }

    private String cityAndKeyQuery() {
        return "SELECT p.name FROM people p WHERE p.city = 'Paris' AND p._key = :key";
    }

    private String orderedLimit() {
        return "SELECT r.value FROM rows r ORDER BY r.value LIMIT 3";
    }

    private String joinQuery() {
        return "SELECT o.total FROM people p JOIN orders o "
                + "ON p._key = o.customerId ORDER BY o.total";
    }

    private record Person(String name, String city, int score, String nickname) {
    }

    private record Order(String customerId, int total) {
    }

    private static final class ScanCountingMap<K, V> extends NitroMap<K, V> {

        private int scans;

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            scans++;
            return super.entrySet();
        }

        int scans() {
            return scans;
        }

        void resetScans() {
            scans = 0;
        }
    }
}
