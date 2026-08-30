package dev.nitromap.query;

import dev.nitromap.cluster.KeyPartitioner;

import java.nio.ByteBuffer;
import java.util.List;

final class ValuePartitioner {

    private ValuePartitioner() {
    }

    static int partition(Object value, int partitions) {
        return bucket(KeyPartitioner.hash(bytes(value)), partitions);
    }

    static int partition(List<Object> values, int partitions) {
        long hash = 0xcbf29ce484222325L;
        for (Object value : values) hash = mix(hash, bytes(value));
        return bucket(hash, partitions);
    }

    private static byte[] bytes(Object value) {
        if (value instanceof Number number)
            return ByteBuffer.allocate(Long.BYTES).putLong(Double.doubleToLongBits(number.doubleValue())).array();
        return ScalarCodec.bytes(value);
    }

    private static long mix(long hash, byte[] bytes) {
        hash = (hash ^ bytes.length) * 0x100000001b3L;
        for (byte value : bytes) hash = (hash ^ Byte.toUnsignedInt(value)) * 0x100000001b3L;
        return hash;
    }

    private static int bucket(long hash, int partitions) {
        return (int) Long.remainderUnsigned(hash, partitions);
    }
}
