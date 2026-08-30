package dev.nitromap;

import dev.nitromap.codec.Codec;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.persistence.LogStore;

import java.io.IOException;
import java.io.Serial;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * A concurrent map with asynchronous, batched persistence.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class NitroMap<K, V> extends ConcurrentHashMap<K, V> implements AutoCloseable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient LogStore<K, V> store;
    private transient volatile List<Consumer<K>> mutationListeners;
    private transient boolean closed;

    /** Opens a persistent UTF-8 string map with best-effort shutdown handling. */
    public static NitroMap<String, String> strings(String directory) {
        return strings(Path.of(directory));
    }

    /** Opens a persistent UTF-8 string map with best-effort shutdown handling. */
    public static NitroMap<String, String> strings(Path directory) {
        return open(directory, Utf8StringCodec.INSTANCE, Utf8StringCodec.INSTANCE);
    }

    /** Opens a persistent map and reports startup failures as unchecked I/O errors. */
    public static <K, V> NitroMap<K, V> open(
            String directory, Codec<K> keys, Codec<V> values) {
        return open(Path.of(directory), keys, values);
    }

    /** Opens a persistent map and reports startup failures as unchecked I/O errors. */
    public static <K, V> NitroMap<K, V> open(
            Path directory, Codec<K> keys, Codec<V> values) {
        try {
            return managed(new NitroMap<>(directory, keys, values));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot open NitroMap at " + directory, exception);
        }
    }

    public NitroMap() {
        super();
        store = null;
    }

    public NitroMap(int initialCapacity) {
        super(initialCapacity);
        store = null;
    }

    public NitroMap(Map<? extends K, ? extends V> entries) {
        super(entries);
        store = null;
    }

    public NitroMap(Path directory, Codec<K> keys, Codec<V> values) throws IOException {
        store = new LogStore<>(directory, keys, values, this::current, this::snapshot,
                this::replayPut, this::replayRemove);
        store.start();
    }

    @Override
    public V put(K key, V value) {
        V previous = super.put(key, value);
        changed(key);
        return previous;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> entries) {
        super.putAll(entries);
        if (store != null) store.markAll(entries.keySet());
        notifyMutations(entries.keySet());
    }

    @Override
    public V get(Object key) {
        return super.get(key);
    }

    @Override
    public V remove(Object key) {
        V previous = super.remove(key);
        if (previous != null) markRemoved(key);
        return previous;
    }

    @Override
    public boolean remove(Object key, Object value) {
        boolean removed = super.remove(key, value);
        if (removed) markRemoved(key);
        return removed;
    }

    public boolean removeAll(Collection<?> keys) {
        boolean changed = false;
        for (Object key : keys) changed |= remove(key) != null;
        return changed;
    }

    public void flush() throws IOException {
        if (store != null) store.flush();
    }

    public void compact() throws IOException {
        if (store != null) store.compact();
    }

    /** Registers a lightweight mutation observer for derived data structures. */
    public synchronized void onMutation(Consumer<K> listener) {
        List<Consumer<K>> listeners = mutationListeners == null
                ? new ArrayList<>() : new ArrayList<>(mutationListeners);
        listeners.add(Objects.requireNonNull(listener));
        mutationListeners = List.copyOf(listeners);
    }

    @Override
    public synchronized void close() throws IOException {
        if (store == null || closed) return;
        closed = true;
        ShutdownRegistry.remove(this);
        store.close();
    }

    private void replayPut(K key, V value) {
        super.put(key, value);
    }

    private V current(K key) {
        return super.get(key);
    }

    private void replayRemove(K key) {
        super.remove(key);
    }

    private Map<K, V> snapshot() {
        return Map.copyOf(this);
    }

    @SuppressWarnings("unchecked")
    private void markRemoved(Object key) {
        changed((K) key);
    }

    private void changed(K key) {
        if (store != null) store.mark(key);
        notifyMutation(key);
    }

    private void notifyMutations(Iterable<? extends K> keys) {
        List<Consumer<K>> listeners = mutationListeners;
        if (listeners != null) keys.forEach(key -> notifyMutation(listeners, key));
    }

    private void notifyMutation(K key) {
        List<Consumer<K>> listeners = mutationListeners;
        if (listeners != null) notifyMutation(listeners, key);
    }

    private void notifyMutation(List<Consumer<K>> listeners, K key) {
        listeners.forEach(listener -> listener.accept(key));
    }

    private static <K, V> NitroMap<K, V> managed(NitroMap<K, V> map) {
        ShutdownRegistry.add(map);
        return map;
    }
}
