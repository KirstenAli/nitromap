package dev.nitromap.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class RowSource {

    private final Source source;
    private final Table table;
    private final PredicateLookup lookup;

    RowSource(Catalog catalog, SqlQuery query, Map<String, ?> parameters) {
        source = query.from();
        table = catalog.table(source.table());
        lookup = PredicateLookup.find(query, table, parameters);
    }

    List<Row> rows() {
        if (lookup == null) return scan();
        if (lookup.key()) return keyed();
        return rows(table.lookup(lookup.column().name(), lookup.value()));
    }

    List<Row> matching(Condition where, Map<String, ?> parameters, int limit) {
        if (limit == 0) return List.of();
        if (lookup != null) return matching(rows(), where, parameters, limit);
        return scan(where, parameters, limit);
    }

    private List<Row> scan(Condition where, Map<String, ?> parameters, int limit) {
        List<Row> rows = new ArrayList<>(Math.min(table.size(), limit));
        for (var entry : table.entries()) {
            add(rows, Row.of(source.alias(), table, entry), where, parameters);
            if (rows.size() == limit) break;
        }
        return rows;
    }

    private List<Row> keyed() {
        TableRow row = table.find(lookup.value());
        return row == null ? new ArrayList<>() : rows(List.of(row));
    }

    private List<Row> scan() {
        List<Row> rows = new ArrayList<>(table.size());
        for (var entry : table.entries()) rows.add(Row.of(source.alias(), table, entry));
        return rows;
    }

    private List<Row> rows(List<TableRow> tableRows) {
        List<Row> rows = new ArrayList<>(tableRows.size());
        tableRows.forEach(row -> rows.add(Row.of(source.alias(), row)));
        return rows;
    }

    private List<Row> matching(List<Row> candidates, Condition where,
                               Map<String, ?> parameters, int limit) {
        List<Row> rows = new ArrayList<>(Math.min(candidates.size(), limit));
        for (Row row : candidates) {
            add(rows, row, where, parameters);
            if (rows.size() == limit) break;
        }
        return rows;
    }

    private void add(List<Row> rows, Row row, Condition where,
                     Map<String, ?> parameters) {
        if (where.test(row, parameters)) rows.add(row);
    }
}
