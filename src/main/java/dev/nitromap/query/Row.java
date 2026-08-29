package dev.nitromap.query;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

final class Row {

    private final Map<String, TableRow> sources;

    private Row(Map<String, TableRow> sources) {
        this.sources = sources;
    }

    static Row of(String alias, Table table, Map.Entry<?, ?> entry) {
        Map<String, TableRow> sources = new LinkedHashMap<>();
        sources.put(normalize(alias), new TableRow(table, entry.getKey(), entry.getValue()));
        return new Row(sources);
    }

    Row add(String alias, TableRow value) {
        Map<String, TableRow> joined = new LinkedHashMap<>(sources);
        joined.put(normalize(alias), value);
        return new Row(joined);
    }

    Object read(ColumnRef column) {
        return column.qualifier() == null ? readUnqualified(column.name()) : readQualified(column);
    }

    Map<String, TableRow> sources() {
        return sources;
    }

    private Object readQualified(ColumnRef column) {
        TableRow row = sources.get(normalize(column.qualifier()));
        if (row == null) throw new IllegalArgumentException("Unknown alias: " + column.qualifier());
        return row.read(column.name());
    }

    private Object readUnqualified(String column) {
        TableRow match = null;
        for (TableRow row : sources.values()) if (row.table().has(column)) match = unique(match, row, column);
        if (match == null) throw new IllegalArgumentException("Unknown column: " + column);
        return match.read(column);
    }

    private TableRow unique(TableRow current, TableRow next, String column) {
        if (current != null) throw new IllegalArgumentException("Ambiguous column: " + column);
        return next;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
