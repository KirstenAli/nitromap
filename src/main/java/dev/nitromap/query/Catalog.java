package dev.nitromap.query;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Catalog {

    private final Map<String, Table> tables = new ConcurrentHashMap<>();

    public <K, V> Catalog add(String name, Map<K, V> data, Schema<V> schema) {
        tables.put(normalize(name), new Table(name, data, schema));
        return this;
    }

    /** Adds an opt-in secondary index to an existing NitroMap-backed table. */
    public Catalog index(String table, String column) {
        table(table).index(column);
        return this;
    }

    Table table(String name) {
        Table table = tables.get(normalize(name));
        if (table == null) throw new IllegalArgumentException("Unknown table: " + name);
        return table;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
