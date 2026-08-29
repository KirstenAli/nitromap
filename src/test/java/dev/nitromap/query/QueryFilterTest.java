package dev.nitromap.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryFilterTest {

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = QueryFixture.engine();
    }

    @Test
    void filtersEquality() {
        assertNames("c.score = 20", "Bob");
    }

    @Test
    void filtersInequality() {
        assertNames("c.score != 20", "Alice", "Cara", "Dan");
    }

    @Test
    void filtersGreaterThan() {
        assertNames("c.score > 30", "Dan");
    }

    @Test
    void filtersGreaterThanOrEqual() {
        assertNames("c.score >= 30", "Cara", "Dan");
    }

    @Test
    void filtersLessThan() {
        assertNames("c.score < 20", "Alice");
    }

    @Test
    void filtersLessThanOrEqual() {
        assertNames("c.score <= 20", "Alice", "Bob");
    }

    @Test
    void filtersBooleanValues() {
        assertNames("c.active = true", "Alice", "Cara");
    }

    @Test
    void filtersNullValues() {
        assertNames("c.nickname = NULL", "Alice", "Bob", "Cara");
    }

    @Test
    void parsesEscapedStrings() {
        assertNames("c.nickname = 'D''Angelo'", "Dan");
    }

    @Test
    void givesAndHigherPrecedenceThanOr() {
        assertNames("c.city = 'London' OR c.city = 'Paris' AND c.name = 'Bob'", "Alice", "Bob", "Cara");
    }

    @Test
    void supportsParenthesizedConditions() {
        assertNames("(c.city = 'London' OR c.city = 'Paris') AND c.name = 'Bob'", "Bob");
    }

    @Test
    void filtersWithNamedParameters() {
        QueryResult result = engine.query(sql("c.score >= :score"), Map.of("score", 30));
        assertEquals(List.of("Cara", "Dan"), names(result));
    }

    private void assertNames(String where, String... expected) {
        assertEquals(List.of(expected), names(engine.query(sql(where))));
    }

    private String sql(String where) {
        return "SELECT c.name FROM customers c WHERE " + where + " ORDER BY c.name";
    }

    private List<Object> names(QueryResult result) {
        return result.rows().stream().map(row -> row.get("name")).toList();
    }
}
