package dev.nitromap.cluster;

import dev.nitromap.codec.Codec;
import dev.nitromap.codec.Utf8StringCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyPartitionerTest {

    @Test
    void hashesEncodedBytesRatherThanObjectHashCodes() {
        KeyPartitioner<Key> first = new KeyPartitioner<>(codec(), 127);
        KeyPartitioner<String> second = new KeyPartitioner<>(Utf8StringCodec.INSTANCE, 127);
        assertEquals(second.partition("customer-1"), first.partition(new Key("customer-1")));
    }

    @Test
    void staysInsideTheLogicalPartitionRange() {
        KeyPartitioner<String> partitioner = new KeyPartitioner<>(Utf8StringCodec.INSTANCE, 31);
        for (int i = 0; i < 10_000; i++) assertTrue(partitioner.partition("key-" + i) < 31);
    }

    @Test
    void distributesOrdinaryKeys() {
        KeyPartitioner<String> partitioner = new KeyPartitioner<>(Utf8StringCodec.INSTANCE, 16);
        long used = java.util.stream.IntStream.range(0, 10_000)
                .mapToObj(i -> partitioner.partition("key-" + i)).distinct().count();
        assertEquals(16, used);
    }

    private Codec<Key> codec() {
        return new Codec<>() {
            public byte[] encode(Key value) { return value.value().getBytes(StandardCharsets.UTF_8); }
            public Key decode(byte[] bytes) { return new Key(new String(bytes, StandardCharsets.UTF_8)); }
        };
    }

    private record Key(String value) {
        @Override public int hashCode() { return 1; }
    }
}
