package dev.nitromap.query;

import dev.nitromap.NitroMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryAggregateTest {

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = QueryFixture.engine();
    }

    @Test
    void calculatesCommonAggregates() {
        assertEquals(Map.of("count", 4L, "sum", 100L, "avg", 25.0,
                "min", 10, "max", 40), row(common()));
    }

    @Test
    void groupsAndOrdersAggregateResults() {
        QueryResult result = engine.query(grouped());
        assertEquals(group("London", 40L, 20.0), result.rows().get(0));
        assertEquals(group("Rome", 40L, 40.0), result.rows().get(1));
        assertEquals(group("Paris", 20L, 20.0), result.rows().get(2));
    }

    @Test
    void ordersByDefaultAggregateLabels() {
        QueryResult result = engine.query("SELECT c.city, SUM(c.score) FROM customers c "
                + "GROUP BY c.city ORDER BY sum DESC, c.city");
        assertEquals("London", result.rows().get(0).get("city"));
    }

    @Test
    void ignoresNullInputs() {
        Map<String, Object> row = row("SELECT COUNT(c.nickname) AS present, "
                + "MIN(c.nickname) AS first, MAX(c.nickname) AS last FROM customers c");
        assertEquals(1L, row.get("present"));
        assertEquals("D'Angelo", row.get("first"));
        assertEquals("D'Angelo", row.get("last"));
    }

    @Test
    void handlesMixedNumericInputs() {
        Map<String, Object> row = mixed().query("SELECT SUM(v.amount) AS total, "
                + "AVG(v.amount) AS average FROM values v").rows().get(0);
        assertEquals(30.5, row.get("total"));
        assertEquals(15.25, row.get("average"));
    }

    @Test
    void returnsSqlEmptyAggregateValues() {
        Map<String, Object> row = QueryFixture.emptyEngine().query(empty()).rows().get(0);
        assertEquals(0L, row.get("count"));
        assertNull(row.get("sum"));
        assertNull(row.get("avg"));
        assertNull(row.get("min"));
        assertNull(row.get("max"));
    }

    @Test
    void aggregatesJoinedRows() {
        assertEquals(Map.of("total", 155L, "average", 38.75,
                "smallest", 0, "largest", 120), row(joined()));
    }

    @Test
    void rejectsNonNumericSumsAndAverages() {
        assertThrows(IllegalArgumentException.class, () -> row("SELECT SUM(c.name) FROM customers c"));
        assertThrows(IllegalArgumentException.class, () -> row("SELECT AVG(c.city) FROM customers c"));
    }

    @Test
    void rejectsWildcardNumericAggregates() {
        assertThrows(IllegalArgumentException.class, () -> row("SELECT SUM(*) FROM customers"));
        assertThrows(IllegalArgumentException.class, () -> row("SELECT MAX(*) FROM customers"));
    }

    private Map<String, Object> row(String sql) {
        return engine.query(sql).rows().get(0);
    }

    private String common() {
        return "SELECT COUNT(c.score), SUM(c.score), AVG(c.score), "
                + "MIN(c.score), MAX(c.score) FROM customers c";
    }

    private String grouped() {
        return "SELECT c.city, SUM(c.score) AS total, AVG(c.score) AS average "
                + "FROM customers c GROUP BY c.city ORDER BY total DESC, c.city";
    }

    private String empty() {
        return "SELECT COUNT(c.score) AS count, SUM(c.score) AS sum, AVG(c.score) AS avg, "
                + "MIN(c.score) AS min, MAX(c.score) AS max FROM customers c";
    }

    private String joined() {
        return "SELECT SUM(o.total) AS total, AVG(o.total) AS average, "
                + "MIN(o.total) AS smallest, MAX(o.total) AS largest FROM customers c "
                + "JOIN orders o ON c._key = o.customerId WHERE c.city = 'London'";
    }

    private Map<String, Object> group(String city, long total, double average) {
        return Map.of("city", city, "total", total, "average", average);
    }

    private QueryEngine mixed() {
        NitroMap<String, Value> values = new NitroMap<>(Map.of(
                "a", new Value(10), "b", new Value(20.5), "c", new Value(null)));
        Schema<Value> schema = Schema.<Value>builder().column("amount", Value::amount).build();
        return new QueryEngine(new Catalog().add("values", values, schema));
    }

    private record Value(Number amount) {
    }
}
