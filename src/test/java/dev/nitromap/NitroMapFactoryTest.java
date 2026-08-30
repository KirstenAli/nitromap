package dev.nitromap;

import dev.nitromap.codec.Utf8StringCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NitroMapFactoryTest {

    private static final Utf8StringCodec UTF_8 = Utf8StringCodec.INSTANCE;

    @TempDir
    Path directory;

    @Test
    void opensStringMapsFromPaths() throws Exception {
        write(NitroMap.strings(directory));
        assertEquals("Ada", read());
    }

    @Test
    void opensStringMapsFromTextPaths() throws Exception {
        write(NitroMap.strings(directory.toString()));
        assertEquals("Ada", read());
    }

    @Test
    void opensCustomCodecsFromTextPaths() throws Exception {
        write(NitroMap.open(directory.toString(), UTF_8, UTF_8));
        assertEquals("Ada", read());
    }

    @Test
    void wrapsOpeningFailures() throws Exception {
        Path file = Files.writeString(directory.resolve("file"), "blocked");
        assertThrows(UncheckedIOException.class, () -> NitroMap.strings(file));
    }

    @Test
    void closesFactoryMapsAtShutdown() throws Exception {
        NitroMap<String, String> first = NitroMap.strings(directory);
        NitroMap<String, String> second = NitroMap.strings(directory.resolve("second"));
        first.put("customer-1", "Ada");
        ShutdownRegistry.closeAll();
        assertEquals("Ada", read());
        assertThrows(IllegalStateException.class, () -> second.put("after", "close"));
    }

    @Test
    void closesFactoryMapsOnlyOnce() throws Exception {
        NitroMap<String, String> map = NitroMap.strings(directory);
        map.close();
        map.close();
    }

    private void write(NitroMap<String, String> map) throws Exception {
        try (map) {
            map.put("customer-1", "Ada");
        }
    }

    private String read() throws Exception {
        try (NitroMap<String, String> map = new NitroMap<>(directory, UTF_8, UTF_8)) {
            return map.get("customer-1");
        }
    }
}
