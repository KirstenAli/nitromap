package dev.nitromap;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NitroMapTest {

    @Test
    void exposesTheConcurrentMapApi() {
        assertInstanceOf(ConcurrentMap.class, NitroMap.memory());
    }

    @Test
    void keepsCollectionViewsReadOnly() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        assertThrows(UnsupportedOperationException.class, () -> map.keySet().remove("count"));
        assertThrows(UnsupportedOperationException.class, () -> map.values().clear());
        assertThrows(UnsupportedOperationException.class, () -> map.entrySet().iterator().next().setValue(2));
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
    void supportsComputedOperationsInMemory() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        map.compute("count", (key, count) -> count + 1);
        assertEquals(2, map.get("count"));
    }

    @Test
    void supportsMergedOperationsInMemory() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        map.merge("count", 2, Integer::sum);
        assertEquals(3, map.get("count"));
    }

    @Test
    void mergesAtomicallyUnderContention() {
        NitroMap<String, Integer> map = NitroMap.memory();
        IntStream.range(0, 10_000).parallel()
                .forEach(index -> map.merge("count", 1, Integer::sum));
        assertEquals(10_000, map.get("count"));
    }

    @Test
    void supportsReplacementAndClearInMemory() {
        NitroMap<String, Integer> map = new NitroMap<>(Map.of("count", 1));
        map.replaceAll((key, value) -> value + 1);
        map.clear();
        assertTrue(map.isEmpty());
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

    @Test
    void reportsSupportedMutationsToObservers() {
        NitroMap<String, Integer> map = new NitroMap<>();
        MutationProbe probe = new MutationProbe();
        map.onMutation(probe::record);
        mutate(map);
        assertEquals(Set.of("first", "second", "third"), probe.keys());
        assertEquals(4, probe.calls());
    }

    private void mutate(NitroMap<String, Integer> map) {
        map.put("first", 1);
        map.putAll(Map.of("second", 2, "third", 3));
        map.remove("second");
    }

    private static final class MutationProbe {

        private final Set<String> keys = ConcurrentHashMap.newKeySet();
        private int calls;

        void record(String key) {
            keys.add(key);
            calls++;
        }

        Set<String> keys() {
            return keys;
        }

        int calls() {
            return calls;
        }
    }
}
