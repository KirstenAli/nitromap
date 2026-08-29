package dev.nitromap.query;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

final class Table {

    private final String name;
    private final Map<?, ?> data;
    private final BiFunction<Object, String, Object> reader;
    private final List<String> columns;

    @SuppressWarnings("unchecked")
    <K, V> Table(String name, Map<K, V> data, Schema<V> schema) {
        this.name = name;
        this.data = data;
        this.reader = (value, column) -> schema.read((V) value, column);
        this.columns = schema.names();
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

    Object read(Object value, String column) {
        return reader.apply(value, column);
    }

    boolean has(String column) {
        return "_key".equalsIgnoreCase(column) || columns.stream().anyMatch(column::equalsIgnoreCase);
    }

    List<String> columns() {
        return columns;
    }
}
