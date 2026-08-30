package dev.nitromap.query;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DistributedProjector {

    private final SqlQuery query;
    private final Path directory;
    private final int memoryRows;

    DistributedProjector(SqlQuery query, Path directory, int memoryRows) {
        this.query = query;
        this.directory = directory;
        this.memoryRows = memoryRows;
    }

    PartitionedRows filter(PartitionedRows input, Map<String, ?> parameters) {
        PartitionedRows output = output(input.partitions());
        try {
            for (int i = 0; i < input.partitions(); i++) for (DataRow row : input.get(i))
                if (query.where().test(row, parameters)) output.add(i, row);
            output.finish();
            return output;
        } catch (RuntimeException error) {
            output.close();
            throw error;
        } finally {
            input.close();
        }
    }

    PartitionedRows project(PartitionedRows input) {
        PartitionedRows output = output(input.partitions());
        try {
            for (int i = 0; i < input.partitions(); i++) for (DataRow row : input.get(i))
                output.add(i, project(row));
            output.finish();
            return output;
        } catch (RuntimeException error) {
            output.close();
            throw error;
        } finally {
            input.close();
        }
    }

    DataRow project(DataRow row) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (SelectItem item : query.select()) add(values, item, row);
        return new DataRow(values);
    }

    private void add(Map<String, Object> values, SelectItem item, DataRow row) {
        if (item.value() instanceof Wildcard) values.putAll(row.values());
        else values.put(item.label(), row.read((ColumnRef) item.value()));
    }

    private PartitionedRows output(int partitions) {
        return new PartitionedRows(partitions, directory, memoryRows);
    }
}
