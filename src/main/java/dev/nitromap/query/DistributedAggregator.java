package dev.nitromap.query;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DistributedAggregator {

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
        try {
            AggregateGroup.validate(query);
            return aggregateInput(input);
        } finally {
            input.close();
        }
    }

    private PartitionedRows aggregateInput(PartitionedRows input) {
        try (PartitionedRows partials = partials(input)) {
            return combine(partials);
        }
    }

    private PartitionedRows partials(PartitionedRows input) {
        PartitionedRows output = output();
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
        Map<GroupKey, AggregateGroup> groups = new HashMap<>();
        for (DataRow row : rows) accept(groups, row, output);
        flush(groups, output);
    }

    private void accept(Map<GroupKey, AggregateGroup> groups, DataRow row,
                        PartitionedRows output) {
        List<Object> values = AggregateGroup.values(query, row);
        groups.computeIfAbsent(new GroupKey(values), ignored -> group(values)).add(row);
        if (groups.size() >= memoryRows) flush(groups, output);
    }

    private void flush(Map<GroupKey, AggregateGroup> groups, PartitionedRows output) {
        groups.values().forEach(group -> output.add(partition(group), partial(group)));
        groups.clear();
    }

    private int partition(AggregateGroup group) {
        return ValuePartitioner.partition(group.values(), partitions);
    }

    private DataRow partial(AggregateGroup group) {
        return new DataRow(group.partial());
    }

    private PartitionedRows combine(PartitionedRows partials) {
        PartitionedRows output = output();
        try {
            for (int i = 0; i < partitions; i++) combine(partials.get(i), output.get(i));
            addEmpty(output);
            output.finish();
            return output;
        } catch (RuntimeException error) {
            output.close();
            throw error;
        }
    }

    private void addEmpty(PartitionedRows output) {
        if (output.size() == 0 && query.groups().isEmpty()) output.add(0, result(group(List.of())));
    }

    private void combine(RowStore partials, RowStore output) {
        try (RowStore sorted = new ExternalSorter(directory, memoryRows).sort(partials, comparator())) {
            reduce(sorted, output);
        }
    }

    private void reduce(RowStore rows, RowStore output) {
        AggregateGroup current = null;
        for (DataRow row : rows) current = reduce(current, row, output);
        if (current != null) output.add(result(current));
    }

    private AggregateGroup reduce(AggregateGroup current, DataRow row, RowStore output) {
        List<Object> values = AggregateGroup.partialValues(query, row);
        if (current != null && equal(current.values(), values)) {
            current.merge(row);
            return current;
        }
        if (current != null) output.add(result(current));
        AggregateGroup next = group(values);
        next.merge(row);
        return next;
    }

    private DataRow result(AggregateGroup group) {
        return new DataRow(group.result());
    }

    private AggregateGroup group(List<Object> values) {
        return new AggregateGroup(query, values);
    }

    private Comparator<DataRow> comparator() {
        return (left, right) -> compare(AggregateGroup.partialValues(query, left),
                AggregateGroup.partialValues(query, right));
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
        for (int i = 0; i < left.size(); i++)
            if (!Values.equal(left.get(i), right.get(i))) return false;
        return true;
    }

    private PartitionedRows output() {
        return new PartitionedRows(partitions, directory, memoryRows);
    }
}
