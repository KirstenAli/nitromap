package dev.nitromap.cluster;

import dev.nitromap.codec.Codec;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Stable FNV-1a partitioning over the key codec bytes. */
public final class KeyPartitioner<K> {

    private static final long OFFSET = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private final Codec<K> keys;
    private final int partitions;

    public KeyPartitioner(Codec<K> keys, int partitions) {
        if (partitions < 1) throw new IllegalArgumentException("partitions must be positive");
        this.keys = java.util.Objects.requireNonNull(keys);
        this.partitions = partitions;
    }

    public int partition(K key) {
        try {
            return (int) Long.remainderUnsigned(hash(keys.encode(key)), partitions);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot encode cluster key", exception);
        }
    }

    public static long hash(byte[] bytes) {
        long hash = OFFSET;
        for (byte value : bytes) hash = (hash ^ Byte.toUnsignedInt(value)) * PRIME;
        return hash;
    }
}
