package dev.nitromap.query;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class DistributedJoiner {

    private final List<QueryShard> shards;
    private final int partitions;
    private final int memoryRows;
    private final Path directory;

    DistributedJoiner(List<QueryShard> shards, int partitions,
                      int memoryRows, Path directory) {
        this.shards = shards;
        this.partitions = partitions;
        this.memoryRows = memoryRows;
        this.directory = directory;
    }

    PartitionedRows join(PartitionedRows rows, JoinSpec join) {
        JoinColumns columns;
        try {
            columns = columns(join);
        } catch (RuntimeException error) {
            rows.close();
            throw error;
        }
        PartitionedRows left = null;
        PartitionedRows right = null;
        try {
            left = shuffle(rows, columns.existing());
            right = shuffle(scan(join.source()), columns.joined());
            return combine(left, right, columns);
        } finally {
            if (left != null) left.close();
            if (right != null) right.close();
        }
    }

    private PartitionedRows scan(Source source) {
        PartitionedRows rows = output(shards.size());
        try {
            for (int i = 0; i < shards.size(); i++)
                for (DataRow row : shards.get(i).scan(source)) rows.add(i, row);
            rows.finish();
            return rows;
        } catch (RuntimeException error) {
            rows.close();
            throw error;
        }
    }

    private PartitionedRows shuffle(PartitionedRows input, ColumnRef column) {
        PartitionedRows output = output(partitions);
        try {
            for (int i = 0; i < input.partitions(); i++) for (DataRow row : input.get(i))
                output.add(ValuePartitioner.partition(row.read(column), partitions), row);
            output.finish();
            return output;
        } catch (RuntimeException error) {
            output.close();
            throw error;
        } finally {
            input.close();
        }
    }

    private PartitionedRows combine(PartitionedRows left, PartitionedRows right,
                                    JoinColumns columns) {
        PartitionedRows result = output(partitions);
        try {
            for (int i = 0; i < partitions; i++) join(left.get(i), right.get(i), result.get(i), columns);
            result.finish();
            return result;
        } catch (RuntimeException error) {
            result.close();
            throw error;
        }
    }

    private void join(RowStore left, RowStore right, RowStore output, JoinColumns columns) {
        if (left.size() <= memoryRows) indexed(left, right, output, columns.existing(), columns.joined(), true);
        else if (right.size() <= memoryRows) indexed(right, left, output, columns.joined(), columns.existing(), false);
        else if (left.size() <= right.size()) blocked(left, right, output, columns, true);
        else blocked(right, left, output, columns, false);
    }

    private void indexed(RowStore build, RowStore probe, RowStore output,
                         ColumnRef buildKey, ColumnRef probeKey, boolean buildLeft) {
        Map<Object, List<DataRow>> index = index(build, buildKey);
        for (DataRow row : probe) add(output, index.get(Values.indexKey(row.read(probeKey))), row, buildLeft);
    }

    private Map<Object, List<DataRow>> index(Iterable<DataRow> rows, ColumnRef key) {
        Map<Object, List<DataRow>> index = new HashMap<>();
        for (DataRow row : rows)
            index.computeIfAbsent(Values.indexKey(row.read(key)), ignored -> new ArrayList<>()).add(row);
        return index;
    }

    private void blocked(RowStore build, RowStore probe, RowStore output,
                         JoinColumns columns, boolean buildLeft) {
        Iterator<DataRow> rows = build.iterator();
        while (rows.hasNext()) block(rows, probe, output, columns, buildLeft);
    }

    private void block(Iterator<DataRow> rows, RowStore probe, RowStore output,
                       JoinColumns columns, boolean buildLeft) {
        ColumnRef buildKey = buildLeft ? columns.existing() : columns.joined();
        ColumnRef probeKey = buildLeft ? columns.joined() : columns.existing();
        Map<Object, List<DataRow>> index = index(chunk(rows), buildKey);
        for (DataRow row : probe) add(output, index.get(Values.indexKey(row.read(probeKey))), row, buildLeft);
    }

    private List<DataRow> chunk(Iterator<DataRow> rows) {
        List<DataRow> chunk = new ArrayList<>(memoryRows);
        while (rows.hasNext() && chunk.size() < memoryRows) chunk.add(rows.next());
        return chunk;
    }

    private void add(RowStore output, List<DataRow> matches,
                     DataRow probe, boolean buildLeft) {
        if (matches == null) return;
        for (DataRow build : matches) output.add(buildLeft ? build.join(probe) : probe.join(build));
    }

    private PartitionedRows output(int partitions) {
        return new PartitionedRows(partitions, directory, memoryRows);
    }

    private JoinColumns columns(JoinSpec join) {
        String alias = join.source().alias();
        boolean left = qualifiedBy(join.left(), alias);
        boolean right = qualifiedBy(join.right(), alias);
        if (left == right) throw new IllegalArgumentException("JOIN must reference one joined-table column");
        return left ? new JoinColumns(join.right(), join.left())
                : new JoinColumns(join.left(), join.right());
    }

    private boolean qualifiedBy(ColumnRef column, String alias) {
        return column.qualifier() != null && column.qualifier().equalsIgnoreCase(alias);
    }

    private record JoinColumns(ColumnRef existing, ColumnRef joined) {
    }
}
