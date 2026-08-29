package dev.nitromap.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QueryEdgeCaseTest {

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = QueryFixture.engine();
    }

    @Test
    void supportsDefaultAliasesAndTrailingSemicolons() {
        QueryResult result = engine.query("SELECT name FROM customers WHERE _key = 'c1';");
        assertEquals("Alice", result.rows().get(0).get("name"));
    }

    @Test
    void supportsAlternativeInequalitySyntax() {
        assertEquals(List.of("Alice", "Cara", "Dan"), names("c.score <> 20"));
    }

    @Test
    void comparesDecimalAndLeftHandLiterals() {
        assertEquals(List.of("Bob", "Cara", "Dan"), names("c.score > 19.5 AND -1 < c.score"));
    }

    @Test
    void ordersNullsLastWhenAscending() {
        List<Map<String, Object>> rows = nicknames("ASC");
        assertEquals("D'Angelo", rows.get(0).get("nickname"));
        assertNull(rows.get(rows.size() - 1).get("nickname"));
    }

    @Test
    void ordersNullsFirstWhenDescending() {
        List<Map<String, Object>> rows = nicknames("DESC");
        assertNull(rows.get(0).get("nickname"));
        assertEquals("D'Angelo", rows.get(rows.size() - 1).get("nickname"));
    }

    @Test
    void joinsWhenTheJoinedKeyIsOnTheLeft() {
        QueryResult result = engine.query(leftKeyJoin());
        assertEquals("Alice", result.rows().get(0).get("name"));
    }

    @Test
    void groupsByMultipleColumns() {
        QueryResult result = engine.query(grouped());
        assertEquals(Map.of("city", "London", "active", true, "total", 2L), result.rows().get(0));
        assertEquals(3, result.size());
    }

    @Test
    void expandsEveryTableInJoinedWildcards() {
        Map<String, Object> row = engine.query(wildcardJoin()).rows().get(0);
        assertEquals("o1", row.get("o._key"));
        assertEquals("Alice", row.get("c.name"));
    }

    private List<Object> names(String where) {
        String sql = "SELECT c.name FROM customers c WHERE " + where + " ORDER BY c.name";
        return engine.query(sql).rows().stream().map(row -> row.get("name")).toList();
    }

    private List<Map<String, Object>> nicknames(String direction) {
        String sql = "SELECT c.nickname FROM customers c ORDER BY c.nickname " + direction;
        return engine.query(sql).rows();
    }

    private String leftKeyJoin() {
        return "SELECT c.name FROM orders o INNER JOIN customers c "
                + "ON c._key = o.customerId WHERE o._key = 'o1'";
    }

    private String grouped() {
        return "SELECT c.city, c.active, COUNT(*) AS total FROM customers c "
                + "GROUP BY c.city, c.active ORDER BY c.city, c.active";
    }

    private String wildcardJoin() {
        return "SELECT * FROM orders o JOIN customers c "
                + "ON o.customerId = c._key WHERE o._key = 'o1'";
    }
}
