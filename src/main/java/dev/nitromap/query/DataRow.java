package dev.nitromap.query;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class DataRow implements ValueRow {

    private final Map<String, Object> values;
    private final Map<String, Object> qualified;

    DataRow(Map<String, Object> values) {
        this.values = new LinkedHashMap<>(values);
        this.qualified = new HashMap<>();
        values.forEach((name, value) -> qualified.put(normalize(name), value));
    }

    static DataRow of(Source source, Table table, Map.Entry<?, ?> entry) {
        return of(source, new TableRow(table, entry.getKey(), entry.getValue()));
    }

    static DataRow of(Source source, TableRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(source.alias() + "._key", row.key());
        row.table().columns().forEach(column -> values.put(source.alias() + "." + column,
                row.read(column)));
        return new DataRow(values);
    }

    DataRow join(DataRow other) {
        Map<String, Object> joined = new LinkedHashMap<>(values);
        joined.putAll(other.values);
        return new DataRow(joined);
    }

    Map<String, Object> values() {
        return values;
    }

    @Override
    public Object read(ColumnRef column) {
        if (column.qualifier() != null) return qualified(column.qualified());
        return unqualified(column.name());
    }

    private Object qualified(String name) {
        String normalized = normalize(name);
        if (qualified.containsKey(normalized)) return qualified.get(normalized);
        throw new IllegalArgumentException("Unknown column: " + name);
    }

    private Object unqualified(String name) {
        Object match = null;
        boolean found = false;
        for (var entry : values.entrySet()) if (column(entry.getKey()).equalsIgnoreCase(name)) {
            if (found) throw new IllegalArgumentException("Ambiguous column: " + name);
            match = entry.getValue();
            found = true;
        }
        if (!found) throw new IllegalArgumentException("Unknown column: " + name);
        return match;
    }

    private String column(String name) {
        return name.substring(name.indexOf('.') + 1);
    }

    private String normalize(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
