package dev.nitromap.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class SecondaryIndex {

    private final Table table;
    private final String column;
    private final ConcurrentHashMap<Object, Set<Object>> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Object, Object> values = new ConcurrentHashMap<>();

    SecondaryIndex(Table table, String column) {
        this.table = table;
        this.column = column;
    }

    void build() {
        for (var entry : table.entries()) refresh(entry.getKey());
    }

    void refresh(Object key) {
        values.compute(key, (ignored, previous) -> replace(key, previous));
    }

    List<TableRow> find(Object value) {
        Set<Object> keys = buckets.get(Values.indexKey(value));
        if (keys == null) return List.of();
        List<TableRow> rows = new ArrayList<>(keys.size());
        for (Object key : keys) add(rows, table.find(key), value);
        return rows;
    }

    private Object replace(Object key, Object previous) {
        TableRow row = table.find(key);
        Object next = row == null ? null : Values.indexKey(row.read(column));
        if (java.util.Objects.equals(previous, next)) return next;
        remove(previous, key);
        add(next, key);
        return next;
    }

    private void add(List<TableRow> rows, TableRow row, Object value) {
        if (row != null && Values.equal(row.read(column), value)) rows.add(row);
    }

    private void add(Object value, Object key) {
        if (value != null) buckets.compute(value, (ignored, keys) -> add(keys, key));
    }

    private Set<Object> add(Set<Object> keys, Object key) {
        Set<Object> result = keys == null ? ConcurrentHashMap.newKeySet() : keys;
        result.add(key);
        return result;
    }

    private void remove(Object value, Object key) {
        if (value != null) buckets.computeIfPresent(value,
                (ignored, keys) -> remove(keys, key));
    }

    private Set<Object> remove(Set<Object> keys, Object key) {
        keys.remove(key);
        return keys.isEmpty() ? null : keys;
    }
}
