package dev.nitromap.query;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

final class DistributedFinisher {

    private final SqlQuery query;
    private final Path directory;
    private final int memoryRows;

    DistributedFinisher(SqlQuery query, Path directory, int memoryRows) {
        this.query = query;
        this.directory = directory;
        this.memoryRows = memoryRows;
    }

    RowStore finish(PartitionedRows input) {
        try {
            return query.orders().isEmpty() ? limit(input) : order(input);
        } finally {
            input.close();
        }
    }

    private RowStore limit(PartitionedRows input) {
        RowStore output = new RowStore(directory, memoryRows);
        for (int i = 0; i < input.partitions() && output.size() < query.limit(); i++)
            copy(input.get(i), output);
        output.finish();
        return output;
    }

    private void copy(RowStore input, RowStore output) {
        for (DataRow row : input) {
            if (output.size() == query.limit()) return;
            output.add(row);
        }
    }

    private RowStore order(PartitionedRows input) {
        Comparator<Map<String, Object>> rows = new Orderer(query).comparator();
        Comparator<DataRow> comparator = (left, right) -> rows.compare(left.values(), right.values());
        return new ExternalSorter(directory, memoryRows).sort(input, comparator, query.limit());
    }
}
