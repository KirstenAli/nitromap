package dev.nitromap.http;

import dev.nitromap.codec.Codec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BinaryBatchCodec<K, V> {

    private static final int MAX_FIELD_BYTES = 256 * 1024 * 1024;

    private final Codec<K> keys;
    private final Codec<V> values;

    public BinaryBatchCodec(Codec<K> keys, Codec<V> values) {
        this.keys = keys;
        this.values = values;
    }

    public byte[] encodeEntries(Map<? extends K, ? extends V> entries) throws IOException {
        return encode(output -> writeEntries(output, entries));
    }

    public Map<K, V> decodeEntries(byte[] bytes) throws IOException {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        Map<K, V> entries = new LinkedHashMap<>();
        while (input.hasRemaining()) entries.put(keys.decode(field(input)), values.decode(field(input)));
        return entries;
    }

    public byte[] encodeKeys(Iterable<? extends K> keys) throws IOException {
        return encode(output -> writeKeys(output, keys));
    }

    public List<K> decodeKeys(byte[] bytes) throws IOException {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        List<K> keys = new ArrayList<>();
        while (input.hasRemaining()) keys.add(this.keys.decode(field(input)));
        return keys;
    }

    private byte[] encode(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
        }
        return bytes.toByteArray();
    }

    private void writeEntries(DataOutputStream output,
                              Map<? extends K, ? extends V> entries) throws IOException {
        for (var entry : entries.entrySet()) {
            field(output, keys.encode(entry.getKey()));
            field(output, values.encode(entry.getValue()));
        }
    }

    private void writeKeys(DataOutputStream output, Iterable<? extends K> keys) throws IOException {
        for (K key : keys) field(output, this.keys.encode(key));
    }

    private void field(DataOutputStream output, byte[] bytes) throws IOException {
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private byte[] field(ByteBuffer input) throws IOException {
        if (input.remaining() < Integer.BYTES) throw new IOException("Incomplete batch field");
        int length = input.getInt();
        if (length < 0 || length > MAX_FIELD_BYTES || length > input.remaining())
            throw new IOException("Invalid batch field size");
        byte[] bytes = new byte[length];
        input.get(bytes);
        return bytes;
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }
}
