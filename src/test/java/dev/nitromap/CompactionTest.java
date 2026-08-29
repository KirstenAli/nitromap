package dev.nitromap;

import dev.nitromap.codec.Codec;
import dev.nitromap.codec.Utf8StringCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionTest {

    private static final Utf8StringCodec UTF_8 = Utf8StringCodec.INSTANCE;

    @TempDir
    Path directory;

    @Test
    void shrinksAnUpdateHeavyLog() throws Exception {
        writeVersions(20);
        long previousSize = Files.size(log());
        compact();
        assertTrue(Files.size(log()) < previousSize);
        assertEquals("value-19", read().get("key"));
    }

    @Test
    void preservesUpdatesAndRemovals() throws Exception {
        write(Map.of("first", "1", "second", "2", "third", "3"));
        updateAndCompact();
        assertEquals(Map.of("first", "updated", "third", "3"), read());
    }

    @Test
    void compactsAnEmptyMapToAnEmptyLog() throws Exception {
        write(Map.of("temporary", "value"));
        removeFlushAndCompact("temporary");
        assertEquals(0, Files.size(log()));
    }

    @Test
    void keepsWritesThatRaceWithCompaction() throws Exception {
        writeWhileCompacting(500);
        assertEquals(500, read().size());
    }

    @Test
    void removesTheTemporaryCompactionFile() throws Exception {
        write(Map.of("key", "value"));
        compact();
        assertFalse(Files.exists(compactingLog()));
    }

    @Test
    void keepsTheOldLogWhenCompactionFails() throws Exception {
        ToggleCodec values = new ToggleCodec();
        failCompaction(values);
        assertEquals("value", read().get("key"));
    }

    @Test
    void plainMapsCanCompact() throws Exception {
        new NitroMap<>().compact();
    }

    private void writeVersions(int count) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            for (int index = 0; index < count; index++) writeVersion(map, index);
        }
    }

    private void writeVersion(NitroMap<String, String> map, int index) throws Exception {
        map.put("key", "value-" + index);
        map.flush();
    }

    private void updateAndCompact() throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.put("first", "updated");
            map.remove("second");
            map.compact();
        }
    }

    private void removeFlushAndCompact(String key) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.remove(key);
            map.flush();
            map.compact();
        }
    }

    private void writeWhileCompacting(int count) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            CompletableFuture<Void> writes = writes(map, count);
            map.compact();
            writes.join();
        }
    }

    private CompletableFuture<Void> writes(NitroMap<String, String> map, int count) {
        return CompletableFuture.runAsync(() -> IntStream.range(0, count).parallel()
                .forEach(index -> map.put("k" + index, "v" + index)));
    }

    private void write(Map<String, String> entries) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.putAll(entries);
        }
    }

    private void compact() throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.compact();
        }
    }

    private void failCompaction(ToggleCodec values) throws Exception {
        try (NitroMap<String, String> map = new NitroMap<>(directory, UTF_8, values)) {
            map.put("key", "value");
            map.flush();
            values.failing = true;
            assertThrows(IOException.class, map::compact);
            values.failing = false;
        }
    }

    private Map<String, String> read() throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            return Map.copyOf(map);
        }
    }

    private NitroMap<String, String> persistentMap() throws Exception {
        return new NitroMap<>(directory, UTF_8, UTF_8);
    }

    private Path log() {
        return directory.resolve("nitromap.log");
    }

    private Path compactingLog() {
        return directory.resolve("nitromap.log.compacting");
    }

    private static final class ToggleCodec implements Codec<String> {

        private boolean failing;

        @Override
        public byte[] encode(String value) throws IOException {
            if (failing) throw new IOException("Expected test failure");
            return UTF_8.encode(value);
        }

        @Override
        public String decode(byte[] bytes) {
            return UTF_8.decode(bytes);
        }
    }
}
