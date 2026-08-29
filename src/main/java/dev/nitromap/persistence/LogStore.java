package dev.nitromap.persistence;

import dev.nitromap.codec.Codec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class LogStore<K, V> implements AutoCloseable {

    private static final long BATCH_NANOS = 2_000_000;

    private final Map<K, PendingChange> dirty = new ConcurrentHashMap<>();
    private final LogFile<K, V> file;
    private final Function<K, V> current;
    private final Supplier<Map<K, V>> snapshot;
    private final Thread writer;
    private final Object ioLock = new Object();

    private volatile IOException failure;
    private volatile boolean running = true;

    public LogStore(Path directory, Codec<K> keys, Codec<V> values,
                    Function<K, V> current, Supplier<Map<K, V>> snapshot,
                    BiConsumer<K, V> loader, Consumer<K> remover) throws IOException {
        Files.createDirectories(directory);
        file = open(directory.resolve("nitromap.log"), keys, values, loader, remover);
        this.current = current;
        this.snapshot = snapshot;
        writer = new Thread(this::writeLoop, "nitromap-writer");
        writer.setDaemon(true);
    }

    public void start() {
        writer.start();
    }

    public void mark(K key) {
        ensureHealthy();
        dirty.put(key, new PendingChange());
    }

    public void markAll(Iterable<? extends K> keys) {
        ensureHealthy();
        keys.forEach(key -> dirty.put(key, new PendingChange()));
    }

    public void flush() throws IOException {
        while (!dirty.isEmpty() && failure == null) LockSupport.parkNanos(BATCH_NANOS);
        checkFailure();
        sync();
    }

    public void compact() throws IOException {
        ensureHealthy();
        synchronized (ioLock) {
            Map<K, PendingChange> pending = Map.copyOf(dirty);
            file.compact(snapshot.get());
            pending.forEach(dirty::remove);
        }
    }

    @Override
    public void close() throws IOException {
        stop();
        join();
        finish();
    }

    private void stop() {
        running = false;
        LockSupport.unpark(writer);
    }

    private void writeLoop() {
        try {
            writeUntilClosed();
        } catch (IOException exception) {
            fail(exception);
        } catch (RuntimeException exception) {
            fail(new IOException("Persistence writer failed", exception));
        }
    }

    private void writeUntilClosed() throws IOException {
        while (running || !dirty.isEmpty()) {
            LockSupport.parkNanos(BATCH_NANOS);
            writeBatch();
        }
    }

    private void writeBatch() throws IOException {
        synchronized (ioLock) {
            writeDirty();
        }
    }

    private void writeDirty() throws IOException {
        boolean changed = !dirty.isEmpty();
        for (var entry : dirty.entrySet()) write(entry);
        if (changed) file.force();
    }

    private void write(Map.Entry<K, PendingChange> entry) throws IOException {
        PendingChange pending = entry.getValue();
        V value = current.apply(entry.getKey());
        if (value == null) file.delete(entry.getKey());
        else file.append(entry.getKey(), value);
        dirty.remove(entry.getKey(), pending);
    }

    private void ensureHealthy() {
        if (failure != null) throw new UncheckedIOException(failure);
        if (!running) throw new IllegalStateException("NitroMap is closed");
    }

    private void checkFailure() throws IOException {
        if (failure != null) throw failure;
    }

    private void fail(IOException exception) {
        failure = exception;
        running = false;
    }

    private void finish() throws IOException {
        synchronized (ioLock) {
            closeFile();
        }
    }

    private void closeFile() throws IOException {
        try {
            checkFailure();
            file.force();
        } finally {
            file.close();
        }
    }

    private void sync() throws IOException {
        synchronized (ioLock) {
            file.force();
        }
    }

    private void join() throws IOException {
        try {
            writer.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while closing NitroMap", exception);
        }
    }

    private static <K, V> LogFile<K, V> open(Path path, Codec<K> keys, Codec<V> values,
                                              BiConsumer<K, V> loader,
                                              Consumer<K> remover) throws IOException {
        LogFile<K, V> file = new LogFile<>(path, keys, values);
        try {
            file.load(loader, remover);
            return file;
        } catch (IOException exception) {
            file.close();
            throw exception;
        }
    }
}
