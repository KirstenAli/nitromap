package dev.nitromap.http;

import dev.nitromap.codec.Utf8StringCodec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryBatchCodecTest {

    private final BinaryBatchCodec<String, String> codec = new BinaryBatchCodec<>(
            Utf8StringCodec.INSTANCE, Utf8StringCodec.INSTANCE);

    @Test
    void roundTripsEntries() throws Exception {
        Map<String, String> entries = Map.of("first", "1", "second", "2");
        assertEquals(entries, codec.decodeEntries(codec.encodeEntries(entries)));
    }

    @Test
    void roundTripsKeys() throws Exception {
        List<String> keys = List.of("first", "second");
        assertEquals(keys, codec.decodeKeys(codec.encodeKeys(keys)));
    }

    @Test
    void roundTripsEmptyBatches() throws Exception {
        assertEquals(Map.of(), codec.decodeEntries(codec.encodeEntries(Map.of())));
        assertEquals(List.of(), codec.decodeKeys(codec.encodeKeys(List.of())));
    }

    @Test
    void rejectsIncompleteFields() {
        assertThrows(IOException.class, () -> codec.decodeKeys(new byte[]{0, 0}));
    }

    @Test
    void rejectsInvalidFieldLengths() {
        assertThrows(IOException.class, () -> codec.decodeKeys(new byte[]{-1, -1, -1, -1}));
    }

    @Test
    void rejectsEntriesWithoutValues() throws Exception {
        byte[] keyOnly = codec.encodeKeys(List.of("key"));
        assertThrows(IOException.class, () -> codec.decodeEntries(keyOnly));
    }
}
