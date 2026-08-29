package dev.nitromap;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NitroMapTest {

    @Test
    void isAConcurrentHashMap() {
        assertInstanceOf(ConcurrentHashMap.class, new NitroMap<>());
    }

    @Test
    void returnsThePreviousValue() {
        NitroMap<String, Integer> map = new NitroMap<>();
        map.put("count", 1);
        assertEquals(1, map.put("count", 2));
    }

    @Test
    void acceptsAnInitialCapacity() {
        NitroMap<String, Integer> map = new NitroMap<>(32);
        map.put("count", 1);
        assertEquals(1, map.get("count"));
    }

    @Test
    void acceptsInitialEntries() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 3));
        assertEquals(3, map.get("count"));
    }

    @Test
    void acceptsBulkWrites() {
        NitroMap<String, Integer> map = new NitroMap<>();
        map.putAll(Map.of("first", 1, "second", 2));
        assertEquals(Map.of("first", 1, "second", 2), map);
    }

    @Test
    void removesAndReturnsThePreviousValue() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        assertEquals(1, map.remove("count"));
        assertEquals(Map.of(), map);
    }

    @Test
    void removesOnlyMatchingValues() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        map.remove("count", 2);
        assertEquals(1, map.get("count"));
    }

    @Test
    void reportsSuccessfulConditionalRemovals() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        assertTrue(map.remove("count", 1));
        assertTrue(map.isEmpty());
    }

    @Test
    void removesCollectionsOfKeys() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("first", 1, "second", 2));
        assertTrue(map.removeAll(Set.of("first", "missing")));
        assertFalse(map.removeAll(Set.of("missing")));
        assertEquals(Map.of("second", 2), map);
    }

    @Test
    void retainsInheritedOperationsInMemory() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        map.compute("count", (key, count) -> count + 1);
        assertEquals(2, map.get("count"));
    }

    @Test
    void plainMapsCanFlushAndClose() throws Exception {
        NitroMap<String, String> map = new NitroMap<>();
        map.flush();
        map.close();
    }

    @Test
    void emptyBulkRemovalChangesNothing() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        assertFalse(map.removeAll(Set.of()));
        assertEquals(1, map.size());
    }
}
