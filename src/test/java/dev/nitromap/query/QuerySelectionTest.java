package dev.nitromap.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QuerySelectionTest {

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = QueryFixture.engine();
    }

    @Test
    void selectsQualifiedColumns() {
        assertEquals(List.of("Alice", "Bob", "Cara", "Dan"), names("SELECT c.name FROM customers c ORDER BY c.name"));
    }

    @Test
    void selectsWildcardColumnsAndKeys() {
        Map<String, Object> row = query("SELECT * FROM customers c WHERE c._key = 'c1'").rows().get(0);
        assertEquals("c1", row.get("c._key"));
        assertEquals("Alice", row.get("c.name"));
    }

    @Test
    void ordersWildcardRows() {
        Map<String, Object> row = query("SELECT * FROM customers c ORDER BY c.name LIMIT 1").rows().get(0);
        assertEquals("Alice", row.get("c.name"));
    }

    @Test
    void supportsAliasesAndCaseInsensitiveNames() {
        QueryResult result = query("select C.NAME AS person from CUSTOMERS C where C._key = 'c1'");
        assertEquals("Alice", result.rows().get(0).get("person"));
    }

    @Test
    void countsAllRowsWithoutGrouping() {
        assertEquals(4L, query("SELECT COUNT(*) AS total FROM customers").rows().get(0).get("total"));
    }

    @Test
    void countsAnEmptyTable() {
        QueryResult result = QueryFixture.emptyEngine().query("SELECT COUNT(*) AS total FROM customers");
        assertEquals(0L, result.rows().get(0).get("total"));
    }

    @Test
    void returnsImmutableResults() {
        QueryResult result = query("SELECT c.name FROM customers c LIMIT 1");
        assertThrows(UnsupportedOperationException.class, () -> result.rows().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.rows().get(0).clear());
    }

    @Test
    void reusesAParsedQuery() {
        String sql = "SELECT c.name FROM customers c WHERE c.score > :score ORDER BY c.name";
        assertEquals(List.of("Cara", "Dan"), names(engine.query(sql, Map.of("score", 20))));
        assertEquals(List.of("Dan"), names(engine.query(sql, Map.of("score", 30))));
    }

    private QueryResult query(String sql) {
        return engine.query(sql);
    }

    private List<Object> names(String sql) {
        return names(query(sql));
    }

    private List<Object> names(QueryResult result) {
        return result.rows().stream().map(row -> row.get("name")).toList();
    }
}
