package dev.nitromap.query;

import dev.nitromap.NitroMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistributedQueryTest {

    @TempDir
    Path directory;

    private DistributedQueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = builder().node("node-a", first()).node("node-b", second()).build();
    }

    @Test
    void filtersAndProjectsAcrossNodes() {
        String sql = "SELECT c.name FROM customers c WHERE c.city = 'London' OR c.score >= 40 ORDER BY c.name";
        assertEquals(List.of("Alice", "Cara", "Dan"), values(query(sql), "name"));
    }

    @Test
    void usesParametersAcrossNodes() {
        String sql = "SELECT c.name, c.score FROM customers c WHERE c.score >= :score ORDER BY c.score DESC";
        assertEquals(List.of("Dan", "Cara"), values(query(sql, Map.of("score", 30)), "name"));
    }

    @Test
    void shuffleJoinsRowsStoredOnDifferentNodes() {
        String sql = "SELECT c.name, o.total FROM customers c JOIN orders o "
                + "ON c._key = o.customerId ORDER BY o.total DESC";
        assertEquals(List.of(300, 200, 120, 30, 5, 0), values(query(sql), "total"));
    }

    @Test
    void composesJoinFilterGroupOrderAndLimit() {
        String sql = "SELECT c.city, COUNT(*) AS total FROM orders o JOIN customers c "
                + "ON o.customerId = c._key WHERE o.total >= 30 GROUP BY c.city "
                + "ORDER BY total DESC, c.city LIMIT 2";
        assertEquals(List.of(Map.of("city", "London", "total", 2L),
                Map.of("city", "Paris", "total", 1L)), query(sql));
    }

    @Test
    void partiallyAggregatesThenCombinesGroups() {
        String sql = "SELECT c.city, COUNT(*) AS total FROM customers c "
                + "GROUP BY c.city ORDER BY total DESC, c.city";
        assertEquals(List.of(Map.of("city", "London", "total", 2L),
                Map.of("city", "Paris", "total", 1L),
                Map.of("city", "Rome", "total", 1L)), query(sql));
    }

    @Test
    void groupsWithoutAnAggregate() {
        String sql = "SELECT c.city FROM customers c GROUP BY c.city ORDER BY c.city";
        assertEquals(List.of("London", "Paris", "Rome"), values(query(sql), "city"));
    }

    @Test
    void countsAnEmptyDistributedInput() {
        String sql = "SELECT COUNT(*) AS total FROM customers c WHERE c.score > 100";
        assertEquals(List.of(Map.of("total", 0L)), query(sql));
    }

    @Test
    void appliesGlobalOrderingBeforeLimit() {
        String sql = "SELECT c.name, c.score FROM customers c ORDER BY c.score DESC LIMIT 2";
        assertEquals(List.of("Dan", "Cara"), values(query(sql), "name"));
    }

    @Test
    void safelyStopsAnUnorderedLimit() {
        try (DistributedQueryResult result = engine.query("SELECT c.name FROM customers c LIMIT 1")) {
            assertEquals(1, result.size());
        }
    }

    @Test
    void returnsNothingForZeroLimit() {
        try (DistributedQueryResult result = engine.query("SELECT c.name FROM customers c LIMIT 0")) {
            assertEquals(0, result.size());
            assertFalse(result.spilled());
        }
    }

    @Test
    void directKeyPredicatesAvoidFullResultScans() {
        String sql = "SELECT c.name FROM customers c WHERE c._key = :key";
        assertEquals(List.of("Cara"), values(query(sql, Map.of("key", "c3")), "name"));
    }

    @Test
    void joinsEqualNumbersWithDifferentJavaTypes() {
        DistributedQueryEngine numeric = builder().node("a", numericFirst())
                .node("b", numericSecond()).build();
        String sql = "SELECT a.name, b.total FROM accounts a JOIN bills b ON a._key = b.accountId";
        assertEquals(List.of(Map.of("name", "Ada", "total", 50)), query(numeric, sql));
    }

    @Test
    void boundsHotJoinBuildsAndSpillsLargeOutput() {
        DistributedQueryEngine skewed = builder().node("a", skew(0)).node("b", skew(1)).build();
        String sql = "SELECT l.name, r.total FROM customers l JOIN orders r ON l.city = r.city";
        try (DistributedQueryResult result = skewed.query(sql)) {
            assertEquals(16, result.size());
            assertTrue(result.spilled());
        }
    }

    @Test
    void spillsAndDeletesTemporaryResults() throws Exception {
        DistributedQueryResult result = engine.query("SELECT c.name FROM customers c");
        assertTrue(result.spilled());
        assertTrue(files() > 0);
        result.close();
        assertEquals(0, files());
    }

    @Test
    void keepsSmallResultsInMemory() {
        try (DistributedQueryResult result = engine.query("SELECT c.name FROM customers c LIMIT 1")) {
            assertFalse(result.spilled());
        }
    }

    @Test
    void returnsImmutableRows() {
        try (DistributedQueryResult result = engine.query("SELECT c.name FROM customers c LIMIT 1")) {
            Map<String, Object> row = result.iterator().next();
            assertThrows(UnsupportedOperationException.class, () -> row.put("name", "changed"));
        }
    }

    @Test
    void validatesDistributedParameters() {
        String sql = "SELECT c.name FROM customers c WHERE c.city = :city";
        assertThrows(IllegalArgumentException.class, () -> engine.query(sql));
    }

    @Test
    void appliesLocalGroupingValidation() throws Exception {
        String sql = "SELECT c.name, COUNT(*) FROM customers c GROUP BY c.city";
        assertThrows(IllegalArgumentException.class, () -> engine.query(sql));
        assertEquals(0, files());
    }

    @Test
    void validatesClusterQueryConfiguration() {
        assertThrows(IllegalStateException.class, () -> DistributedQueryEngine.builder().build());
        assertThrows(IllegalArgumentException.class, () -> builder().shufflePartitions(0));
        assertThrows(IllegalArgumentException.class, () -> builder().maxRowsInMemory(0));
    }

    @Test
    void rejectsDuplicateNodeNames() {
        var builder = builder().node("same", first()).node("SAME", second());
        assertThrows(IllegalStateException.class, builder::build);
    }

    private DistributedQueryEngine.Builder builder() {
        return DistributedQueryEngine.builder().shufflePartitions(3)
                .maxRowsInMemory(2).spillDirectory(directory);
    }

    private List<Map<String, Object>> query(String sql) {
        return query(engine, sql, Map.of());
    }

    private List<Map<String, Object>> query(String sql, Map<String, ?> parameters) {
        return query(engine, sql, parameters);
    }

    private List<Map<String, Object>> query(DistributedQueryEngine engine, String sql) {
        return query(engine, sql, Map.of());
    }

    private List<Map<String, Object>> query(DistributedQueryEngine engine, String sql,
                                            Map<String, ?> parameters) {
        try (DistributedQueryResult result = engine.query(sql, parameters)) {
            return result.rows();
        }
    }

    private List<Object> values(List<Map<String, Object>> rows, String name) {
        return rows.stream().map(row -> row.get(name)).toList();
    }

    private long files() throws Exception {
        if (!Files.exists(directory)) return 0;
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }

    private Catalog first() {
        return catalog(Map.of("c1", customer("Alice", "London", 10),
                        "c2", customer("Bob", "Paris", 20)),
                Map.of("o3", order("c2", 200), "o4", order("c3", 5), "o6", order("c4", 300)));
    }

    private Catalog second() {
        return catalog(Map.of("c3", customer("Cara", "London", 30),
                        "c4", customer("Dan", "Rome", 40)),
                Map.of("o1", order("c1", 120), "o2", order("c1", 30), "o5", order("c3", 0)));
    }

    private Catalog catalog(Map<String, Customer> customers, Map<String, Order> orders) {
        return new Catalog().add("customers", new NitroMap<>(customers), customerSchema())
                .add("orders", new NitroMap<>(orders), orderSchema());
    }

    private Catalog numericFirst() {
        return new Catalog().add("accounts", new NitroMap<Integer, Account>(Map.of(1, new Account("Ada"))), accountSchema())
                .add("bills", new NitroMap<Long, Bill>(), billSchema());
    }

    private Catalog numericSecond() {
        return new Catalog().add("accounts", new NitroMap<Integer, Account>(), accountSchema())
                .add("bills", new NitroMap<Long, Bill>(Map.of(2L, new Bill(1L, 50))), billSchema());
    }

    private Catalog skew(int node) {
        Map<String, Customer> customers = node == 0 ? Map.of(
                "c1", customer("A", "hot", 1), "c2", customer("B", "hot", 2)) : Map.of(
                "c3", customer("C", "hot", 3), "c4", customer("D", "hot", 4));
        Map<String, Order> orders = node == 0 ? Map.of(
                "o1", order("hot", 1), "o2", order("hot", 2)) : Map.of(
                "o3", order("hot", 3), "o4", order("hot", 4));
        return catalog(customers, orders);
    }

    private Schema<Customer> customerSchema() {
        return Schema.<Customer>builder().column("name", Customer::name)
                .column("city", Customer::city).column("score", Customer::score).build();
    }

    private Schema<Order> orderSchema() {
        return Schema.<Order>builder().column("customerId", Order::customerId)
                .column("city", Order::customerId).column("total", Order::total).build();
    }

    private Schema<Account> accountSchema() {
        return Schema.<Account>builder().column("name", Account::name).build();
    }

    private Schema<Bill> billSchema() {
        return Schema.<Bill>builder().column("accountId", Bill::accountId)
                .column("total", Bill::total).build();
    }

    private Customer customer(String name, String city, int score) {
        return new Customer(name, city, score);
    }

    private Order order(String customerId, int total) {
        return new Order(customerId, total);
    }

    private record Customer(String name, String city, int score) {
    }

    private record Order(String customerId, int total) {
    }

    private record Account(String name) {
    }

    private record Bill(long accountId, int total) {
    }
}
