package dev.nitromap.query;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DistributedAggregator {

    private static final String GROUP = "@group.";
    private static final String COUNT = "@group.count";

    private final SqlQuery query;
    private final int partitions;
    private final int memoryRows;
    private final Path directory;

    DistributedAggregator(SqlQuery query, int partitions,
                          int memoryRows, Path directory) {
        this.query = query;
        this.partitions = partitions;
        this.memoryRows = memoryRows;
        this.directory = directory;
    }

    PartitionedRows aggregate(PartitionedRows input) {
        PartitionedRows partials = null;
        try {
            validate();
            partials = partials(input);
            return combine(partials);
        } finally {
            input.close();
            if (partials != null) partials.close();
        }
    }

    private PartitionedRows partials(PartitionedRows input) {
        PartitionedRows output = output(partitions);
        try {
            for (int i = 0; i < input.partitions(); i++) partial(input.get(i), output);
            output.finish();
            return output;
        } catch (RuntimeException error) {
            output.close();
            throw error;
        }
    }

    private void partial(RowStore rows, PartitionedRows output) {
        Map<GroupKey, Group> groups = new HashMap<>();
        for (DataRow row : rows) {
            List<Object> values = query.groups().stream().map(row::read).toList();
            groups.computeIfAbsent(new GroupKey(values), ignored -> new Group(values)).add();
            if (groups.size() >= memoryRows) flush(groups, output);
        }
        flush(groups, output);
    }

    private void flush(Map<GroupKey, Group> groups, PartitionedRows output) {
        groups.values().forEach(group -> output.add(
                ValuePartitioner.partition(group.values(), partitions), partial(group)));
        groups.clear();
    }

    private DataRow partial(Group group) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < group.values().size(); i++) values.put(GROUP + i, group.values().get(i));
        values.put(COUNT, group.count());
        return new DataRow(values);
    }

    private PartitionedRows combine(PartitionedRows partials) {
        PartitionedRows output = output(partitions);
        try {
            for (int i = 0; i < partitions; i++) combine(partials.get(i), output.get(i));
            if (output.size() == 0 && query.groups().isEmpty()) output.add(0, result(List.of(), 0));
            output.finish();
            return output;
        } catch (RuntimeException error) {
            output.close();
            throw error;
        }
    }

    private void combine(RowStore partials, RowStore output) {
        RowStore sorted = new ExternalSorter(directory, memoryRows).sort(partials, comparator());
        try {
            reduce(sorted, output);
        } finally {
            sorted.close();
        }
    }

    private void reduce(RowStore sorted, RowStore output) {
        List<Object> values = null;
        long count = 0;
        for (DataRow row : sorted) {
            List<Object> next = values(row);
            if (values != null && !equal(values, next)) output.add(result(values, count));
            if (values == null || !equal(values, next)) { values = next; count = 0; }
            count += ((Number) row.values().get(COUNT)).longValue();
        }
        if (values != null) output.add(result(values, count));
    }

    private Comparator<DataRow> comparator() {
        return (left, right) -> compare(values(left), values(right));
    }

    private int compare(List<Object> left, List<Object> right) {
        for (int i = 0; i < left.size(); i++) {
            int value = Values.compareNullable(left.get(i), right.get(i));
            if (value != 0) return value;
        }
        return 0;
    }

    private boolean equal(List<Object> left, List<Object> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!Values.equal(left.get(i), right.get(i))) return false;
        return true;
    }

    private List<Object> values(DataRow row) {
        List<Object> values = new ArrayList<>(query.groups().size());
        for (int i = 0; i < query.groups().size(); i++) values.add(row.values().get(GROUP + i));
        return values;
    }

    private DataRow result(List<Object> groups, long count) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (SelectItem item : query.select()) values.put(item.label(), value(item, groups, count));
        return new DataRow(values);
    }

    private Object value(SelectItem item, List<Object> groups, long count) {
        if (item.value() instanceof CountAll) return count;
        ColumnRef column = (ColumnRef) item.value();
        for (int i = 0; i < query.groups().size(); i++)
            if (same(query.groups().get(i), column)) return groups.get(i);
        throw new IllegalArgumentException("Selected column must appear in GROUP BY: " + column.qualified());
    }

    private void validate() {
        if (query.select().stream().anyMatch(item -> item.value() instanceof Wildcard))
            throw new IllegalArgumentException("GROUP BY cannot select *");
        query.select().forEach(item -> { if (!(item.value() instanceof CountAll)) value(item, emptyGroups(), 0); });
    }

    private List<Object> emptyGroups() {
        return java.util.Collections.nCopies(query.groups().size(), null);
    }

    private boolean same(ColumnRef left, ColumnRef right) {
        if (!left.name().equalsIgnoreCase(right.name())) return false;
        return left.qualifier() == null || right.qualifier() == null
                || left.qualifier().equalsIgnoreCase(right.qualifier());
    }

    private PartitionedRows output(int partitions) {
        return new PartitionedRows(partitions, directory, memoryRows);
    }

    private static final class Group {

        private final List<Object> values;
        private long count;

        Group(List<Object> values) {
            this.values = values;
        }

        void add() {
            count++;
        }

        List<Object> values() {
            return values;
        }

        long count() {
            return count;
        }
    }

    private static final class GroupKey {

        private final List<Object> values;

        GroupKey(List<Object> values) {
            this.values = values.stream().map(Values::indexKey).toList();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof GroupKey key && values.equals(key.values);
        }

        @Override
        public int hashCode() {
            return values.hashCode();
        }
    }
}
