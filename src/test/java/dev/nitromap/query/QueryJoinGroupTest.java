package dev.nitromap.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryJoinGroupTest {

    private static final String SHOWCASE_QUERY = """
            SELECT c.city,
                   c.name,
                   COUNT(*) AS order_count,
                   SUM(o.total) AS total_sales,
                   AVG(o.total) AS average_order,
                   MIN(o.total) AS smallest_order,
                   MAX(o.total) AS largest_order
            FROM customers c
            JOIN orders o ON c._key = o.customerId
            WHERE (c.city = :primaryCity OR c.city = :secondaryCity)
              AND c.score >= :minimumScore
              AND o.total >= :minimumOrder
            GROUP BY c.city, c.name
            ORDER BY total_sales DESC, average_order DESC, c.city, c.name
            LIMIT 20
            """;

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = QueryFixture.engine();
    }

    @Test
    void usesDirectKeyJoins() {
        QueryResult result = engine.query(directJoin(), Map.of("minimum", 100, "special", "Cara"));
        assertEquals(group("London", 2), result.rows().get(0));
        assertEquals(group("Paris", 1), result.rows().get(1));
    }

    @Test
    void usesHashJoinsForNonKeyColumns() {
        QueryResult result = engine.query(hashJoin());
        assertEquals(List.of(120, 30), totals(result));
    }

    @Test
    void groupsWithoutAnAggregate() {
        QueryResult result = engine.query("SELECT c.city FROM customers c GROUP BY c.city ORDER BY c.city");
        assertEquals(List.of("London", "Paris", "Rome"), values(result, "city"));
    }

    @Test
    void ordersAscendingAndDescendingColumns() {
        QueryResult result = engine.query("SELECT c.city, c.name FROM customers c ORDER BY c.city ASC, c.name DESC");
        assertEquals(List.of("Cara", "Alice", "Bob", "Dan"), values(result, "name"));
    }

    @Test
    void supportsZeroLimits() {
        assertEquals(0, engine.query("SELECT c.name FROM customers c LIMIT 0").size());
    }

    @Test
    void countsNoMatchingRows() {
        QueryResult result = engine.query("SELECT COUNT(*) AS total FROM customers c WHERE c.score > 100");
        assertEquals(0L, result.rows().get(0).get("total"));
    }

    @Test
    void composesTheReadmeShowcaseQuery() {
        QueryResult result = engine.query(SHOWCASE_QUERY, showcaseParameters());
        assertEquals(showcaseRows(), result.rows());
    }

    private String directJoin() {
        return """
                SELECT c.city, COUNT(*) AS order_count
                FROM orders o JOIN customers c ON o.customerId = c._key
                WHERE o.total >= :minimum OR (c.name = :special AND o.total > 0)
                GROUP BY c.city ORDER BY order_count DESC, c.city ASC LIMIT 2
                """;
    }

    private String hashJoin() {
        return """
                SELECT c.name, o.total FROM customers c
                JOIN orders o ON c._key = o.customerId
                WHERE c.city = 'London' ORDER BY o.total DESC LIMIT 2
                """;
    }

    private Map<String, Object> group(String city, long count) {
        return Map.of("city", city, "order_count", count);
    }

    private Map<String, Object> showcaseParameters() {
        return Map.of("primaryCity", "London", "secondaryCity", "Paris",
                "minimumScore", 10, "minimumOrder", 25);
    }

    private List<Map<String, Object>> showcaseRows() {
        return List.of(showcase("Paris", "Bob", 1L, 200L, 200.0, 200, 200),
                showcase("London", "Alice", 2L, 150L, 75.0, 30, 120));
    }

    private Map<String, Object> showcase(String city, String name, long count,
                                         long sales, double average, int smallest, int largest) {
        return Map.of("city", city, "name", name, "order_count", count,
                "total_sales", sales, "average_order", average,
                "smallest_order", smallest, "largest_order", largest);
    }

    private List<Object> totals(QueryResult result) {
        return values(result, "total");
    }

    private List<Object> values(QueryResult result, String column) {
        return result.rows().stream().map(row -> row.get(column)).toList();
    }
}
