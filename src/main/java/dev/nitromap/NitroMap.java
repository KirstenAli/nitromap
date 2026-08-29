package dev.nitromap;

import dev.nitromap.codec.Codec;
import dev.nitromap.persistence.LogStore;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
        if (store != null) store.mark(key);
        return previous;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> entries) {
        super.putAll(entries);
        if (store != null) store.markAll(entries.keySet());
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

    @Override
    public void close() throws IOException {
        if (store != null) store.close();
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
        if (store != null) store.mark((K) key);
    }
}
