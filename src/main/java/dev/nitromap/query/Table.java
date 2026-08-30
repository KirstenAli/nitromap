package dev.nitromap.query;

import dev.nitromap.NitroMap;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

final class Table {

    private final String name;
    private final Map<?, ?> data;
    private final BiFunction<Object, String, Object> reader;
    private final List<String> columns;
    private final Map<String, SecondaryIndex> indexes = new ConcurrentHashMap<>();
    private final NitroMap<Object, Object> mutable;

    @SuppressWarnings("unchecked")
    <K, V> Table(String name, Map<K, V> data, Schema<V> schema) {
        this.name = name;
        this.data = data;
        this.reader = (value, column) -> schema.read((V) value, column);
        this.columns = schema.names();
        this.mutable = data instanceof NitroMap<?, ?> map
                ? (NitroMap<Object, Object>) map : null;
    }

    String name() {
        return name;
    }

    int size() {
        return data.size();
    }

    Set<? extends Map.Entry<?, ?>> entries() {
        return data.entrySet();
    }

    TableRow find(Object key) {
        Object value = data.get(key);
        return value == null ? null : new TableRow(this, key, value);
    }

    synchronized void index(String column) {
        requireIndexable(column);
        String name = normalize(column);
        if (indexes.containsKey(name)) return;
        SecondaryIndex index = new SecondaryIndex(this, column);
        mutable.onMutation(index::refresh);
        index.build();
        indexes.put(name, index);
    }

    boolean indexed(String column) {
        return indexes.containsKey(normalize(column));
    }

    List<TableRow> lookup(String column, Object value) {
        return indexes.get(normalize(column)).find(value);
    }

    Object read(Object value, String column) {
        return reader.apply(value, column);
    }

    boolean has(String column) {
        return "_key".equalsIgnoreCase(column) || columns.stream().anyMatch(column::equalsIgnoreCase);
    }

    List<String> columns() {
        return columns;
    }

    private void requireIndexable(String column) {
        if (!has(column) || "_key".equalsIgnoreCase(column))
            throw new IllegalArgumentException("Unknown index column: " + column);
        if (mutable == null)
            throw new IllegalArgumentException("Secondary indexes require NitroMap data");
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
