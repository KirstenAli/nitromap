package dev.nitromap.query;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

final class ExternalSorter {

    private final Path directory;
    private final int memoryRows;

    ExternalSorter(Path directory, int memoryRows) {
        this.directory = directory;
        this.memoryRows = memoryRows;
    }

    RowStore sort(RowStore input, Comparator<DataRow> comparator) {
        List<RowStore> runs = runs(input, comparator);
        RowStore result = merge(runs, comparator, Long.MAX_VALUE);
        runs.forEach(RowStore::close);
        return result;
    }

    RowStore sort(PartitionedRows input, Comparator<DataRow> comparator, long limit) {
        if (limit <= memoryRows) return top(input, comparator, (int) limit);
        List<RowStore> runs = runs(input, comparator);
        RowStore result = merge(runs, comparator, limit);
        runs.forEach(RowStore::close);
        return result;
    }

    private List<RowStore> runs(PartitionedRows input, Comparator<DataRow> comparator) {
        List<RowStore> runs = new ArrayList<>();
        for (int i = 0; i < input.partitions(); i++) runs.addAll(runs(input.get(i), comparator));
        return runs;
    }

    private RowStore top(PartitionedRows input, Comparator<DataRow> comparator, int limit) {
        PriorityQueue<DataRow> top = new PriorityQueue<>(comparator.reversed());
        for (int i = 0; i < input.partitions(); i++) add(top, input.get(i), limit);
        List<DataRow> rows = new ArrayList<>(top);
        rows.sort(comparator);
        RowStore run = new RowStore(directory, memoryRows);
        rows.forEach(run::add);
        run.finish();
        return run;
    }

    private void add(PriorityQueue<DataRow> top, RowStore input, int limit) {
        for (DataRow row : input) {
            top.add(row);
            if (top.size() > limit) top.remove();
        }
    }

    private List<RowStore> runs(RowStore input, Comparator<DataRow> comparator) {
        List<RowStore> runs = new ArrayList<>();
        List<DataRow> chunk = new ArrayList<>(memoryRows);
        for (DataRow row : input) {
            chunk.add(row);
            if (chunk.size() == memoryRows) flush(runs, chunk, comparator);
        }
        if (!chunk.isEmpty()) flush(runs, chunk, comparator);
        return runs;
    }

    private void flush(List<RowStore> runs, List<DataRow> chunk,
                       Comparator<DataRow> comparator) {
        chunk.sort(comparator);
        RowStore run = new RowStore(directory, 0);
        chunk.forEach(run::add);
        run.finish();
        runs.add(run);
        chunk.clear();
    }

    private RowStore merge(List<RowStore> runs, Comparator<DataRow> comparator,
                           long limit) {
        RowStore output = new RowStore(directory, memoryRows);
        PriorityQueue<Cursor> queue = queue(runs, comparator);
        while (!queue.isEmpty() && output.size() < limit) advance(output, queue);
        output.finish();
        return output;
    }

    private PriorityQueue<Cursor> queue(List<RowStore> runs,
                                        Comparator<DataRow> comparator) {
        PriorityQueue<Cursor> queue = new PriorityQueue<>((left, right) -> comparator.compare(left.row(), right.row()));
        runs.stream().map(RowStore::iterator).filter(Iterator::hasNext)
                .map(Cursor::new).forEach(queue::add);
        return queue;
    }

    private void advance(RowStore output, PriorityQueue<Cursor> queue) {
        Cursor cursor = queue.remove();
        output.add(cursor.row());
        if (cursor.advance()) queue.add(cursor);
    }

    private static final class Cursor {

        private final Iterator<DataRow> rows;
        private DataRow row;

        Cursor(Iterator<DataRow> rows) {
            this.rows = rows;
            advance();
        }

        DataRow row() {
            return row;
        }

        boolean advance() {
            if (!rows.hasNext()) return false;
            row = rows.next();
            return true;
        }
    }
}
