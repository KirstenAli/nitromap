package dev.nitromap.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Joiner {

    private final Catalog catalog;

    Joiner(Catalog catalog) {
        this.catalog = catalog;
    }

    List<Row> join(List<Row> rows, JoinSpec join) {
        Table table = catalog.table(join.source().table());
        JoinColumns columns = columns(join);
        return columns.joined().name().equalsIgnoreCase("_key")
                ? direct(rows, join.source(), table, columns.existing())
                : indexed(rows, join.source(), table, columns);
    }

    private List<Row> direct(List<Row> rows, Source source, Table table, ColumnRef key) {
        List<Row> joined = new ArrayList<>(rows.size());
        for (Row row : rows) add(joined, row, source.alias(), table.find(row.read(key)));
        return joined;
    }

    private List<Row> indexed(List<Row> rows, Source source, Table table, JoinColumns columns) {
        Map<Object, List<TableRow>> index = index(table, columns.joined());
        List<Row> joined = new ArrayList<>();
        for (Row row : rows) addAll(joined, row, source.alias(), index.get(row.read(columns.existing())));
        return joined;
    }

    private Map<Object, List<TableRow>> index(Table table, ColumnRef column) {
        Map<Object, List<TableRow>> index = new HashMap<>();
        for (var entry : table.entries()) index(index, table, entry, column);
        return index;
    }

    private void index(Map<Object, List<TableRow>> index, Table table,
                       Map.Entry<?, ?> entry, ColumnRef column) {
        TableRow row = new TableRow(table, entry.getKey(), entry.getValue());
        index.computeIfAbsent(row.read(column.name()), ignored -> new ArrayList<>()).add(row);
    }

    private void add(List<Row> result, Row row, String alias, TableRow joined) {
        if (joined != null) result.add(row.add(alias, joined));
    }

    private void addAll(List<Row> result, Row row, String alias, List<TableRow> matches) {
        if (matches != null) matches.forEach(match -> result.add(row.add(alias, match)));
    }

    private JoinColumns columns(JoinSpec join) {
        String alias = join.source().alias();
        boolean left = qualifiedBy(join.left(), alias);
        boolean right = qualifiedBy(join.right(), alias);
        if (left == right) throw new IllegalArgumentException("JOIN must reference one joined-table column");
        if (left) return new JoinColumns(join.right(), join.left());
        if (right) return new JoinColumns(join.left(), join.right());
        throw new IllegalArgumentException("JOIN must qualify the joined table column");
    }

    private boolean qualifiedBy(ColumnRef column, String alias) {
        return column.qualifier() != null && column.qualifier().equalsIgnoreCase(alias);
    }

    private record JoinColumns(ColumnRef existing, ColumnRef joined) {
    }
}
