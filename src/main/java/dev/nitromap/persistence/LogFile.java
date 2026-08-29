package dev.nitromap.persistence;

import dev.nitromap.codec.Codec;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

final class LogFile<K, V> implements AutoCloseable {

    private static final int MAX_FIELD_BYTES = 256 * 1024 * 1024;

    private final Codec<K> keyCodec;
    private final Codec<V> valueCodec;
    private final Path path;
    private FileChannel channel;

    LogFile(Path path, Codec<K> keyCodec, Codec<V> valueCodec) throws IOException {
        this.keyCodec = keyCodec;
        this.valueCodec = valueCodec;
        this.path = path;
        this.channel = open(path);
    }

    void load(BiConsumer<K, V> target, Consumer<K> remover) throws IOException {
        channel.position(0);
        long validBytes = readAll(target, remover);
        channel.truncate(validBytes).position(validBytes);
    }

    void append(K key, V value) throws IOException {
        byte[] keyBytes = keyCodec.encode(key);
        byte[] valueBytes = valueCodec.encode(value);
        write(record(keyBytes, valueBytes));
    }

    void delete(K key) throws IOException {
        write(tombstone(keyCodec.encode(key)));
    }

    void compact(Map<K, V> entries) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".compacting");
        writeSnapshot(temporary, entries);
        replace(temporary);
    }

    void force() throws IOException {
        channel.force(false);
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private long readAll(BiConsumer<K, V> target, Consumer<K> remover) throws IOException {
        long validBytes = 0;
        try {
            while (hasMore()) validBytes = readOne(target, remover);
        } catch (EOFException ignored) {
            // A partial final record is expected after a sudden process stop.
        }
        return validBytes;
    }

    private boolean hasMore() throws IOException {
        return channel.position() < channel.size();
    }

    private long readOne(BiConsumer<K, V> target, Consumer<K> remover) throws IOException {
        K key = keyCodec.decode(readField());
        int length = readLength(true);
        if (length < 0) remover.accept(key);
        else target.accept(key, valueCodec.decode(readBytes(length)));
        return channel.position();
    }

    private byte[] readField() throws IOException {
        return readBytes(readLength(false));
    }

    private int readLength(boolean tombstoneAllowed) throws IOException {
        int length = ByteBuffer.wrap(readBytes(Integer.BYTES)).getInt();
        if (length < (tombstoneAllowed ? -1 : 0) || length > MAX_FIELD_BYTES)
            throw new IOException("Invalid log field size");
        return length;
    }

    private byte[] readBytes(int length) throws IOException {
        ByteBuffer bytes = ByteBuffer.allocate(length);
        while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
        if (bytes.hasRemaining()) throw new EOFException();
        return bytes.array();
    }

    private ByteBuffer record(byte[] key, byte[] value) {
        ByteBuffer bytes = ByteBuffer.allocate(8 + key.length + value.length);
        bytes.putInt(key.length).put(key).putInt(value.length).put(value);
        return bytes.flip();
    }

    private ByteBuffer tombstone(byte[] key) {
        ByteBuffer bytes = ByteBuffer.allocate(8 + key.length);
        bytes.putInt(key.length).put(key).putInt(-1);
        return bytes.flip();
    }

    private void writeSnapshot(Path temporary, Map<K, V> entries) throws IOException {
        try (FileChannel output = FileChannel.open(temporary, CREATE, WRITE, TRUNCATE_EXISTING)) {
            for (var entry : entries.entrySet()) write(output, encoded(entry));
            output.force(false);
        }
    }

    private ByteBuffer encoded(Map.Entry<K, V> entry) throws IOException {
        return record(keyCodec.encode(entry.getKey()), valueCodec.encode(entry.getValue()));
    }

    private void replace(Path temporary) throws IOException {
        channel.close();
        try {
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING);
        } finally {
            channel = open(path);
            channel.position(channel.size());
        }
    }

    private void write(ByteBuffer bytes) throws IOException {
        write(channel, bytes);
    }

    private void write(FileChannel output, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) output.write(bytes);
    }

    private FileChannel open(Path file) throws IOException {
        return FileChannel.open(file, CREATE, READ, WRITE);
    }
}
