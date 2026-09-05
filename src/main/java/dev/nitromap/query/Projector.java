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
        AggregateGroup.validate(query);
        Map<GroupKey, AggregateGroup> groups = groups();
        rows.forEach(row -> add(groups, row));
        List<Map<String, Object>> result = new ArrayList<>(groups.size());
        groups.values().forEach(group -> result.add(group.result()));
        return result;
    }

    private Map<GroupKey, AggregateGroup> groups() {
        Map<GroupKey, AggregateGroup> groups = new LinkedHashMap<>();
        if (query.groups().isEmpty()) groups.put(new GroupKey(List.of()), group(List.of()));
        return groups;
    }

    private void add(Map<GroupKey, AggregateGroup> groups, Row row) {
        List<Object> values = AggregateGroup.values(query, row);
        groups.computeIfAbsent(new GroupKey(values), ignored -> group(values)).add(row);
    }

    private AggregateGroup group(List<Object> values) {
        return new AggregateGroup(query, values);
    }
}
