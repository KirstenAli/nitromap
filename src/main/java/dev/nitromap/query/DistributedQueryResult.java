package dev.nitromap.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** A pull-based result that may be backed by a temporary spill file. */
public final class DistributedQueryResult implements Iterable<Map<String, Object>>, AutoCloseable {

    private final RowStore rows;

    DistributedQueryResult(RowStore rows) {
        this.rows = rows;
    }

    public long size() {
        return rows.size();
    }

    public boolean spilled() {
        return rows.spilled();
    }

    public List<Map<String, Object>> rows() {
        List<Map<String, Object>> result = new ArrayList<>();
        forEach(result::add);
        return List.copyOf(result);
    }

    public Stream<Map<String, Object>> stream() {
        return StreamSupport.stream(spliterator(), false).onClose(this::close);
    }

    @Override
    public Iterator<Map<String, Object>> iterator() {
        Iterator<DataRow> iterator = rows.iterator();
        return new Iterator<>() {
            public boolean hasNext() { return iterator.hasNext(); }
            public Map<String, Object> next() { return immutable(iterator.next().values()); }
        };
    }

    @Override
    public void close() {
        rows.close();
    }

    private Map<String, Object> immutable(Map<String, Object> row) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }
}
