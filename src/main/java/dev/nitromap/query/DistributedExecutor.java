package dev.nitromap.query;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class DistributedExecutor {

    private final List<QueryShard> shards;
    private final String sql;
    private final SqlQuery query;
    private final Map<String, ?> parameters;
    private final int shufflePartitions;
    private final int memoryRows;
    private final Path directory;

    DistributedExecutor(List<QueryShard> shards, String sql, SqlQuery query,
                        Map<String, ?> parameters, int shufflePartitions,
                        int memoryRows, Path directory) {
        this.shards = shards;
        this.sql = sql;
        this.query = query;
        this.parameters = parameters;
        this.shufflePartitions = shufflePartitions;
        this.memoryRows = memoryRows;
        this.directory = directory;
    }

    RowStore execute() {
        if (query.limit() == 0) return new RowStore(directory, memoryRows);
        if (query.streamable()) return early();
        PartitionedRows rows = scan();
        for (JoinSpec join : query.joins()) rows = join(rows, join);
        DistributedProjector projector = new DistributedProjector(query, directory, memoryRows);
        rows = projector.filter(rows, parameters);
        rows = query.grouped() ? aggregate(rows) : projector.project(rows);
        return new DistributedFinisher(query, directory, memoryRows).finish(rows);
    }

    private RowStore early() {
        RowStore result = new RowStore(directory, memoryRows);
        try {
            for (QueryShard shard : shards) {
                for (DataRow row : shard.project(sql, parameters)) {
                    result.add(row);
                    if (result.size() == query.limit()) return finished(result);
                }
            }
            return finished(result);
        } catch (RuntimeException error) {
            result.close();
            throw error;
        }
    }

    private Iterable<DataRow> candidates(QueryShard shard) {
        Object key = DistributedPredicateLookup.key(query, parameters);
        if (key == null) return shard.scan(query.from());
        DataRow row = shard.lookup(query.from(), key);
        return row == null ? List.of() : List.of(row);
    }

    private PartitionedRows scan() {
        PartitionedRows rows = new PartitionedRows(shards.size(), directory, memoryRows);
        try {
            for (int i = 0; i < shards.size(); i++)
                for (DataRow row : candidates(shards.get(i))) rows.add(i, row);
            rows.finish();
            return rows;
        } catch (RuntimeException error) {
            rows.close();
            throw error;
        }
    }

    private PartitionedRows join(PartitionedRows rows, JoinSpec join) {
        return new DistributedJoiner(shards, shufflePartitions,
                memoryRows, directory).join(rows, join);
    }

    private PartitionedRows aggregate(PartitionedRows rows) {
        return new DistributedAggregator(query, shufflePartitions,
                memoryRows, directory).aggregate(rows);
    }

    private RowStore finished(RowStore rows) {
        rows.finish();
        return rows;
    }
}
