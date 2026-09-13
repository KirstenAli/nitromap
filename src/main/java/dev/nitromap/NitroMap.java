package dev.nitromap;

import dev.nitromap.codec.Codec;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.persistence.LogStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A concurrent map with optional asynchronous, batched persistence.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class NitroMap<K, V> extends AbstractMap<K, V>
        implements ConcurrentMap<K, V>, AutoCloseable {

    private final ConcurrentHashMap<K, V> entries;
    private final Map<K, V> view;
    private final transient LogStore<K, V> store;
    private transient volatile List<Consumer<K>> mutationListeners;
    private transient volatile Evictor<K, V> evictor;
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

    /** Creates an in-memory map with persistence disabled. */
    public static <K, V> NitroMap<K, V> memory() {
        return new NitroMap<>();
    }

    public NitroMap() {
        entries = new ConcurrentHashMap<>();
        view = Collections.unmodifiableMap(entries);
        store = null;
    }

    public NitroMap(int initialCapacity) {
        entries = new ConcurrentHashMap<>(initialCapacity);
        view = Collections.unmodifiableMap(entries);
        store = null;
    }

    public NitroMap(Map<? extends K, ? extends V> initialEntries) {
        entries = new ConcurrentHashMap<>(initialEntries);
        view = Collections.unmodifiableMap(entries);
        store = null;
    }

    public NitroMap(Path directory, Codec<K> keys, Codec<V> values) throws IOException {
        entries = new ConcurrentHashMap<>();
        view = Collections.unmodifiableMap(entries);
        store = new LogStore<>(directory, keys, values, this::current, this::snapshot,
                this::replayPut, this::replayRemove);
        store.start();
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return entries.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return entries.containsValue(value);
    }

    @Override
    public V put(K key, V value) {
        V previous = entries.put(key, value);
        changed(key);
        evict();
        return previous;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> additions) {
        entries.putAll(additions);
        changedAll(additions.keySet());
        evict();
    }

    @Override
    public V get(Object key) {
        return entries.get(key);
    }

    @Override
    public V getOrDefault(Object key, V defaultValue) {
        return entries.getOrDefault(key, defaultValue);
    }

    @Override
    public V remove(Object key) {
        V previous = entries.remove(key);
        if (previous != null) markRemoved(key);
        return previous;
    }

    @Override
    public boolean remove(Object key, Object value) {
        boolean removed = entries.remove(key, value);
        if (removed) markRemoved(key);
        return removed;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        V previous = entries.putIfAbsent(key, value);
        if (previous == null) inserted(key);
        return previous;
    }

    @Override
    public V replace(K key, V value) {
        V previous = entries.replace(key, value);
        if (previous != null) changed(key);
        return previous;
    }

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        boolean replaced = entries.replace(key, oldValue, newValue);
        if (replaced) changed(key);
        return replaced;
    }

    @Override
    public void clear() {
        entries.keySet().forEach(this::remove);
    }

    @Override
    public Set<K> keySet() {
        return view.keySet();
    }

    @Override
    public Collection<V> values() {
        return view.values();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return view.entrySet();
    }

    @Override
    public void forEach(BiConsumer<? super K, ? super V> action) {
        entries.forEach(action);
    }

    public long mappingCount() {
        return entries.mappingCount();
    }

    public boolean removeAll(Collection<?> keys) {
        boolean changed = false;
        for (Object key : keys) changed |= remove(key) != null;
        return changed;
    }

    public void flush() throws IOException {
        flushEvictor();
        if (store != null) store.flush();
    }

    public void compact() throws IOException {
        flushEvictor();
        if (store != null) store.compact();
    }

    /** Enables destructive background eviction above an approximate entry limit. */
    public synchronized NitroMap<K, V> evictAt(int maximumEntries) {
        if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be positive");
        closeEvictor();
        evictor = new Evictor<>(this, maximumEntries);
        evictor.signal();
        return this;
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
        if (closed) return;
        closed = true;
        closeEvictor();
        closeStore();
    }

    private void closeStore() throws IOException {
        if (store == null) return;
        ShutdownRegistry.remove(this);
        store.close();
    }

    private void replayPut(K key, V value) {
        entries.put(key, value);
    }

    private V current(K key) {
        return entries.get(key);
    }

    private void replayRemove(K key) {
        entries.remove(key);
    }

    private Map<K, V> snapshot() {
        return Map.copyOf(entries);
    }

    @SuppressWarnings("unchecked")
    private void markRemoved(Object key) {
        changed((K) key);
    }

    private void changed(K key) {
        if (store != null) store.mark(key);
        notifyMutation(key);
    }

    private void changedAll(Iterable<? extends K> keys) {
        if (store != null) store.markAll(keys);
        notifyMutations(keys);
    }

    private void inserted(K key) {
        changed(key);
        evict();
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

    private void evict() {
        Evictor<K, V> current = evictor;
        if (current != null) current.signal();
    }

    private void closeEvictor() {
        Evictor<K, V> current = evictor;
        if (current != null) current.close();
    }

    private void flushEvictor() {
        Evictor<K, V> current = evictor;
        if (current != null) current.flush();
    }

    private static <K, V> NitroMap<K, V> managed(NitroMap<K, V> map) {
        ShutdownRegistry.add(map);
        return map;
    }
}
