package dev.nitromap.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Projector {

    private final SqlQuery query;

    Projector(SqlQuery query) {
        this.query = query;
    }

    List<Map<String, Object>> project(List<Row> rows) {
        return query.grouped() ? grouped(rows) : plain(rows);
    }

    private List<Map<String, Object>> plain(List<Row> rows) {
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        rows.forEach(row -> result.add(projectRow(row)));
        return result;
    }

    Map<String, Object> projectRow(Row row) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (SelectItem item : query.select()) add(result, item, row);
        return result;
    }

    private void add(Map<String, Object> result, SelectItem item, Row row) {
        if (item.value() instanceof Wildcard) expand(result, row);
        else result.put(item.label(), row.read((ColumnRef) item.value()));
    }

    private void expand(Map<String, Object> result, Row row) {
        row.sources().forEach((alias, value) -> expand(result, alias, value));
    }

    private void expand(Map<String, Object> result, String alias, TableRow row) {
        result.put(alias + "._key", row.key());
        row.table().columns().forEach(column -> result.put(alias + "." + column, row.read(column)));
    }

    private List<Map<String, Object>> grouped(List<Row> rows) {
        List<Map<String, Object>> result = new ArrayList<>();
        groups(rows).values().forEach(group -> result.add(project(group)));
        return result;
    }

    private Map<GroupKey, Group> groups(List<Row> rows) {
        if (query.groups().isEmpty()) return Map.of(new GroupKey(List.of()), Group.of(rows));
        Map<GroupKey, Group> groups = new LinkedHashMap<>();
        rows.forEach(row -> groups.computeIfAbsent(key(row), ignored -> new Group()).add(row));
        return groups;
    }

    private GroupKey key(Row row) {
        return new GroupKey(query.groups().stream().map(row::read).toList());
    }

    private Map<String, Object> project(Group group) {
        Map<String, Object> result = new LinkedHashMap<>();
        query.select().forEach(item -> result.put(item.label(), groupValue(group, item)));
        return result;
    }

    private Object groupValue(Group group, SelectItem item) {
        if (item.value() instanceof CountAll) return group.count();
        if (item.value() instanceof Wildcard) throw new IllegalArgumentException("GROUP BY cannot select *");
        ColumnRef column = (ColumnRef) item.value();
        if (query.groups().stream().noneMatch(grouped -> same(grouped, column)))
            throw new IllegalArgumentException("Selected column must appear in GROUP BY: " + column.qualified());
        return group.value(column);
    }

    private boolean same(ColumnRef left, ColumnRef right) {
        if (!left.name().equalsIgnoreCase(right.name())) return false;
        return left.qualifier() == null || right.qualifier() == null
                || left.qualifier().equalsIgnoreCase(right.qualifier());
    }

    private record GroupKey(List<Object> values) {
    }

    private static final class Group {

        private Row first;
        private long count;

        static Group of(List<Row> rows) {
            Group group = new Group();
            rows.forEach(group::add);
            return group;
        }

        void add(Row row) {
            if (first == null) first = row;
            count++;
        }

        long count() {
            return count;
        }

        Object value(ColumnRef column) {
            return first == null ? null : first.read(column);
        }
    }
}
