package dev.nitromap;

import dev.nitromap.codec.Utf8StringCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvictionTest {

    private static final Utf8StringCodec UTF_8 = Utf8StringCodec.INSTANCE;

    @TempDir
    Path directory;

    @Test
    void keepsEveryEntryByDefault() {
        NitroMap<String, String> map = new NitroMap<>();
        map.putAll(entries(200));
        assertEquals(200, map.size());
    }

    @Test
    void evictsBulkWritesInTheBackground() throws Exception {
        try (NitroMap<String, String> map = new NitroMap<String, String>().evictAt(50)) {
            map.putAll(entries(200));
            awaitSize(map, 50);
        }
    }

    @Test
    void evictsConcurrentWritesSafely() throws Exception {
        try (NitroMap<Integer, Integer> map = new NitroMap<Integer, Integer>().evictAt(100)) {
            IntStream.range(0, 1_000).parallel().forEach(key -> map.put(key, key));
            awaitSize(map, 100);
        }
    }

    @Test
    void rejectsInvalidEntryLimits() {
        NitroMap<String, String> map = new NitroMap<>();
        assertThrows(IllegalArgumentException.class, () -> map.evictAt(0));
    }

    @Test
    void replacesTheExistingEntryLimit() throws Exception {
        try (NitroMap<String, String> map = new NitroMap<>(entries(100))) {
            map.evictAt(50);
            awaitSize(map, 50);
            map.evictAt(10);
            awaitSize(map, 10);
        }
    }

    @Test
    void flushWaitsForBackgroundEviction() throws Exception {
        try (NitroMap<String, String> map = new NitroMap<String, String>().evictAt(10)) {
            map.putAll(entries(100));
            map.flush();
            assertTrue(map.size() <= 10);
        }
    }

    @Test
    void persistsEvictionsAsRemovals() throws Exception {
        writeAndEvict();
        try (NitroMap<String, String> restored = persistentMap()) {
            assertTrue(restored.size() > 0 && restored.size() <= 10);
        }
    }

    private void writeAndEvict() throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.putAll(entries(100));
            map.flush();
            map.evictAt(10);
            awaitSize(map, 10);
        }
    }

    private NitroMap<String, String> persistentMap() throws Exception {
        return new NitroMap<>(directory, UTF_8, UTF_8);
    }

    private Map<String, String> entries(int count) {
        return IntStream.range(0, count).boxed()
                .collect(toMap(key -> "key-" + key, key -> "value-" + key));
    }

    private void awaitSize(Map<?, ?> map, int maximum) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (map.size() > maximum && System.nanoTime() < deadline)
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        assertTrue(map.size() <= maximum);
    }
}
