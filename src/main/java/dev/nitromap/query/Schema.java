package dev.nitromap.query;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class Schema<T> {

    private final Map<String, Column<T>> columns;

    private Schema(Map<String, Column<T>> columns) {
        this.columns = Collections.unmodifiableMap(new LinkedHashMap<>(columns));
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    Object read(T value, String name) {
        return column(name).reader().apply(value);
    }

    boolean has(String name) {
        return columns.containsKey(normalize(name));
    }

    List<String> names() {
        return columns.values().stream().map(Column::name).toList();
    }

    private Column<T> column(String name) {
        Column<T> column = columns.get(normalize(name));
        if (column == null) throw new IllegalArgumentException("Unknown column: " + name);
        return column;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private record Column<T>(String name, Function<T, ?> reader) {
    }

    public static final class Builder<T> {

        private final Map<String, Column<T>> columns = new LinkedHashMap<>();

        public Builder<T> column(String name, Function<T, ?> reader) {
            columns.put(normalize(name), new Column<>(name, reader));
            return this;
        }

        public Schema<T> build() {
            return new Schema<>(columns);
        }
    }
}
