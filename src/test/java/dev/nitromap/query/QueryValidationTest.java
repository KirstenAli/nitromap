package dev.nitromap.query;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;

class QueryValidationTest {

    private QueryEngine engine;

    @BeforeEach
    void setUp() {
        engine = QueryFixture.engine();
    }

    @Test
    void rejectsUnknownTables() {
        assertInvalid("SELECT x.name FROM missing x");
    }

    @Test
    void rejectsUnknownColumns() {
        assertInvalid("SELECT c.missing FROM customers c");
    }

    @Test
    void rejectsAmbiguousColumns() {
        assertInvalid("SELECT _key FROM customers c JOIN orders o ON c._key = o.customerId");
    }

    @Test
    void rejectsMissingParameters() {
        String sql = "SELECT c.name FROM customers c WHERE c.score > :score";
        assertThrows(IllegalArgumentException.class, () -> engine.query(sql, Map.of()));
    }

    @Test
    void rejectsMalformedQueries() {
        assertInvalid("SELECT FROM customers");
    }

    @Test
    void rejectsUnclosedStrings() {
        assertInvalid("SELECT c.name FROM customers c WHERE c.name = 'Alice");
    }

    @Test
    void rejectsNegativeLimits() {
        assertInvalid("SELECT c.name FROM customers c LIMIT -1");
    }

    @Test
    void rejectsUngroupedSelections() {
        assertInvalid("SELECT c.name, COUNT(*) FROM customers c GROUP BY c.city");
    }

    @Test
    void rejectsWildcardGrouping() {
        assertInvalid("SELECT * FROM customers c GROUP BY c.city");
    }

    @Test
    void rejectsJoinConditionsWithoutTheJoinedAlias() {
        assertInvalid("SELECT c.name FROM customers c JOIN orders o ON c._key = c._key");
    }

    @Test
    void rejectsJoinConditionsWithOnlyTheJoinedAlias() {
        assertInvalid("SELECT c.name FROM customers c JOIN orders o ON o._key = o.customerId");
    }

    @Test
    void rejectsUnknownAliases() {
        assertInvalid("SELECT x.name FROM customers c");
    }

    @Test
    void rejectsIncomparableValues() {
        assertInvalid("SELECT c.name FROM customers c WHERE c.name > 10");
    }

    @Test
    void rejectsUnexpectedTrailingTokens() {
        assertInvalid("SELECT c.name FROM customers c; extra");
    }

    private void assertInvalid(String sql) {
        assertThrows(IllegalArgumentException.class, () -> engine.query(sql));
    }
}
