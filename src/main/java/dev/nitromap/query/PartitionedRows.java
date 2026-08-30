package dev.nitromap.query;

import java.nio.file.Path;
import java.util.Arrays;

final class PartitionedRows implements AutoCloseable {

    private final RowStore[] stores;

    PartitionedRows(int partitions, Path directory, int memoryRows) {
        stores = new RowStore[partitions];
        Arrays.setAll(stores, partition -> new RowStore(directory,
                memoryRows / partitions + (partition < memoryRows % partitions ? 1 : 0)));
    }

    int partitions() {
        return stores.length;
    }

    RowStore get(int partition) {
        return stores[partition];
    }

    void add(int partition, DataRow row) {
        stores[partition].add(row);
    }

    long size() {
        return Arrays.stream(stores).mapToLong(RowStore::size).sum();
    }

    boolean spilled() {
        return Arrays.stream(stores).anyMatch(RowStore::spilled);
    }

    void finish() {
        Arrays.stream(stores).forEach(RowStore::finish);
    }

    @Override
    public void close() {
        Arrays.stream(stores).forEach(RowStore::close);
    }
}
