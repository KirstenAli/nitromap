package dev.nitromap;

import dev.nitromap.codec.Codec;
import dev.nitromap.codec.Utf8StringCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static java.nio.file.StandardOpenOption.APPEND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceTest {

    private static final Utf8StringCodec UTF_8 = Utf8StringCodec.INSTANCE;

    @TempDir
    Path directory;

    @Test
    void restoresSingleWrites() throws Exception {
        write("speed", "blazing");
        assertEquals("blazing", read().get("speed"));
    }

    @Test
    void restoresBulkWrites() throws Exception {
        write(Map.of("first", "1", "second", "2"));
        assertEquals(2, read().size());
    }

    @Test
    void restoresOnlyTheLatestValue() throws Exception {
        writeUpdates("speed", "fast", "blazing");
        assertEquals("blazing", read().get("speed"));
    }

    @Test
    void appendsAcrossSessions() throws Exception {
        write("first", "1");
        write("second", "2");
        assertEquals(Map.of("first", "1", "second", "2"), read());
    }

    @Test
    void closeFlushesPendingWrites() throws Exception {
        write("closed", "safe");
        assertEquals("safe", read().get("closed"));
    }

    @Test
    void explicitFlushReachesTheLog() throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.put("flushed", "yes");
            map.flush();
            assertTrue(Files.size(log()) > 0);
        }
    }

    @Test
    void preservesUnicodeBytes() throws Exception {
        write("greeting", "Hello 👋 こんにちは");
        assertEquals("Hello 👋 こんにちは", read().get("greeting"));
    }

    @Test
    void recoversFromAnIncompleteRecord() throws Exception {
        write("complete", "record");
        Files.write(log(), new byte[]{0, 0}, APPEND);
        assertEquals("record", read().get("complete"));
    }

    @Test
    void appendsAfterRecoveringAnIncompleteRecord() throws Exception {
        write("first", "1");
        Files.write(log(), new byte[]{0, 0}, APPEND);
        write("second", "2");
        assertEquals(Map.of("first", "1", "second", "2"), read());
    }

    @Test
    void rejectsAnInvalidRecordLength() throws Exception {
        Files.createDirectories(directory);
        Files.write(log(), ByteBuffer.allocate(4).putInt(-1).array());
        assertThrows(IOException.class, this::persistentMap);
    }

    @Test
    void rejectsInvalidValueLengths() throws Exception {
        Files.createDirectories(directory);
        Files.write(log(), invalidValueLength());
        assertThrows(IOException.class, this::persistentMap);
    }

    @Test
    void createsMissingPersistenceDirectories() throws Exception {
        Path nested = directory.resolve("one/two");
        try (NitroMap<String, String> ignored = persistentMap(nested)) { }
        assertTrue(Files.isRegularFile(nested.resolve("nitromap.log")));
    }

    @Test
    void persistsConcurrentWrites() throws Exception {
        writeConcurrently(500);
        assertEquals(500, read().size());
    }

    @Test
    void reportsCodecFailures() throws Exception {
        NitroMap<String, String> map = new NitroMap<>(directory, UTF_8, new FailingCodec());
        map.put("broken", "value");
        assertThrows(IOException.class, map::flush);
        assertThrows(IOException.class, map::close);
    }

    @Test
    void rejectsWritesAfterAWriterFailure() throws Exception {
        NitroMap<String, String> map = new NitroMap<>(directory, UTF_8, new FailingCodec());
        map.put("broken", "value");
        assertThrows(IOException.class, map::flush);
        assertThrows(UncheckedIOException.class, () -> map.put("next", "value"));
        assertThrows(IOException.class, map::close);
    }

    @Test
    void persistsRemovals() throws Exception {
        write(Map.of("first", "1", "second", "2"));
        remove("first");
        assertEquals(Map.of("second", "2"), read());
    }

    @Test
    void persistsConditionalRemovals() throws Exception {
        write("key", "value");
        removeConditionally("key", "value");
        assertEquals(Map.of(), read());
    }

    @Test
    void keepsValuesAfterFailedConditionalRemovals() throws Exception {
        write("key", "value");
        removeConditionally("key", "different");
        assertEquals("value", read().get("key"));
    }

    @Test
    void persistsBulkRemovals() throws Exception {
        write(Map.of("first", "1", "second", "2", "third", "3"));
        removeAll("first", "third");
        assertEquals(Map.of("second", "2"), read());
    }

    @Test
    void keepsTheLatestPutAfterARemoval() throws Exception {
        write("key", "old");
        removeThenPut("key", "new");
        assertEquals("new", read().get("key"));
    }

    @Test
    void doesNotRestorePutThenRemove() throws Exception {
        putThenRemove("temporary", "value");
        assertEquals(Map.of(), read());
    }

    private void write(String key, String value) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.put(key, value);
        }
    }

    private void write(Map<String, String> entries) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.putAll(entries);
        }
    }

    private void writeUpdates(String key, String first, String second) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.put(key, first);
            map.put(key, second);
        }
    }

    private void writeConcurrently(int count) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            IntStream.range(0, count).parallel().forEach(index -> map.put("k" + index, "v" + index));
        }
    }

    private void remove(String key) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.remove(key);
        }
    }

    private void removeConditionally(String key, String value) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.remove(key, value);
        }
    }

    private void removeAll(String... keys) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.removeAll(List.of(keys));
        }
    }

    private void removeThenPut(String key, String value) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.remove(key);
            map.put(key, value);
        }
    }

    private void putThenRemove(String key, String value) throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            map.put(key, value);
            map.remove(key);
        }
    }

    private Map<String, String> read() throws Exception {
        try (NitroMap<String, String> map = persistentMap()) {
            return Map.copyOf(map);
        }
    }

    private NitroMap<String, String> persistentMap() throws IOException {
        return persistentMap(directory);
    }

    private NitroMap<String, String> persistentMap(Path path) throws IOException {
        return new NitroMap<>(path, UTF_8, UTF_8);
    }

    private Path log() {
        return directory.resolve("nitromap.log");
    }

    private byte[] invalidValueLength() {
        return ByteBuffer.allocate(9).putInt(1).put((byte) 'k').putInt(-2).array();
    }

    private static final class FailingCodec implements Codec<String> {

        @Override
        public byte[] encode(String value) throws IOException {
            throw new IOException("Expected test failure");
        }

        @Override
        public String decode(byte[] bytes) {
            return UTF_8.decode(bytes);
        }
    }
}
