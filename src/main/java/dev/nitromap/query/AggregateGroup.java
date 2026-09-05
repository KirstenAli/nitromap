package dev.nitromap.query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AggregateGroup {

    private static final String GROUP = "@group.";
    private static final String AGGREGATE = "@aggregate.";

    private final SqlQuery query;
    private final List<Object> groups;
    private final List<AggregateState> states;

    AggregateGroup(SqlQuery query, List<Object> groups) {
        this.query = query;
        this.groups = groups;
        this.states = states();
    }

    static void validate(SqlQuery query) {
        query.select().forEach(item -> validate(query, item));
    }

    static List<Object> values(SqlQuery query, ValueRow row) {
        return query.groups().stream().map(row::read).toList();
    }

    static List<Object> partialValues(SqlQuery query, DataRow row) {
        List<Object> values = new ArrayList<>(query.groups().size());
        for (int i = 0; i < query.groups().size(); i++) values.add(row.values().get(group(i)));
        return values;
    }

    void add(ValueRow row) {
        for (int i = 0; i < states.size(); i++) add(i, row);
    }

    void merge(DataRow row) {
        for (int i = 0; i < states.size(); i++) merge(i, row);
    }

    List<Object> values() {
        return groups;
    }

    Map<String, Object> partial() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < groups.size(); i++) values.put(group(i), groups.get(i));
        for (int i = 0; i < states.size(); i++) write(values, i);
        return values;
    }

    Map<String, Object> result() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < query.select().size(); i++) put(values, i);
        return values;
    }

    private static void validate(SqlQuery query, SelectItem item) {
        if (item.value() instanceof Wildcard)
            throw new IllegalArgumentException("GROUP BY cannot select *");
        if (item.value() instanceof ColumnRef column && query.groups().stream().noneMatch(column::same))
            throw new IllegalArgumentException("Selected column must appear in GROUP BY: " + column.qualified());
    }

    private List<AggregateState> states() {
        List<AggregateState> result = new ArrayList<>(query.select().size());
        query.select().forEach(item -> result.add(state(item)));
        return result;
    }

    private AggregateState state(SelectItem item) {
        if (item.value() instanceof AggregateCall call) return AggregateState.create(call.function());
        return null;
    }

    private void add(int index, ValueRow row) {
        if (states.get(index) == null) return;
        AggregateCall call = (AggregateCall) query.select().get(index).value();
        states.get(index).add(call.read(row));
    }

    private void merge(int index, DataRow row) {
        AggregateState state = states.get(index);
        if (state != null) state.merge(parts(row, index, state.width()));
    }

    private List<Object> parts(DataRow row, int index, int size) {
        List<Object> values = new ArrayList<>(size);
        for (int part = 0; part < size; part++) values.add(row.values().get(aggregate(index, part)));
        return values;
    }

    private void write(Map<String, Object> values, int index) {
        AggregateState state = states.get(index);
        if (state == null) return;
        List<Object> parts = state.partial();
        for (int part = 0; part < parts.size(); part++) values.put(aggregate(index, part), parts.get(part));
    }

    private void put(Map<String, Object> values, int index) {
        SelectItem item = query.select().get(index);
        values.put(item.label(), value(item, index));
    }

    private Object value(SelectItem item, int index) {
        if (item.value() instanceof AggregateCall) return states.get(index).result();
        return group((ColumnRef) item.value());
    }

    private Object group(ColumnRef column) {
        for (int i = 0; i < query.groups().size(); i++)
            if (column.same(query.groups().get(i))) return groups.get(i);
        throw new IllegalArgumentException("Selected column must appear in GROUP BY: " + column.qualified());
    }

    private static String group(int index) {
        return GROUP + index;
    }

    private static String aggregate(int index, int part) {
        return AGGREGATE + index + "." + part;
    }
}

record GroupKey(List<Object> values) {

    GroupKey {
        values = values.stream().map(Values::indexKey).toList();
    }
}
