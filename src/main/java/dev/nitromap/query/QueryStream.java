package dev.nitromap.query;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.function.Function;

final class QueryStream implements Iterable<Map<String, Object>> {

    private final Catalog catalog;
    private final SqlQuery query;
    private final Map<String, ?> parameters;

    QueryStream(Catalog catalog, SqlQuery query, Map<String, ?> parameters) {
        if (!query.joins().isEmpty() || query.grouped() || !query.orders().isEmpty())
            throw new IllegalArgumentException("Streaming stage supports scan, filter, projection, and LIMIT only");
        this.catalog = catalog;
        this.query = query;
        this.parameters = parameters;
    }

    @Override
    public Iterator<Map<String, Object>> iterator() {
        return new Rows(candidates(), query, parameters);
    }

    private Iterator<Row> candidates() {
        Table table = catalog.table(query.from().table());
        PredicateLookup lookup = PredicateLookup.find(query, table, parameters);
        if (lookup == null) return scan(table);
        if (lookup.key()) return keyed(table, lookup.value());
        return indexed(table, table.lookup(lookup.column().name(), lookup.value()));
    }

    private Iterator<Row> scan(Table table) {
        return new Mapped<>(table.entries().iterator(),
                entry -> Row.of(query.from().alias(), table, entry));
    }

    private Iterator<Row> keyed(Table table, Object key) {
        TableRow row = table.find(key);
        return indexed(table, row == null ? List.of() : List.of(row));
    }

    private Iterator<Row> indexed(Table table, List<TableRow> rows) {
        return new Mapped<>(rows.iterator(), row -> Row.of(query.from().alias(), row));
    }

    private static final class Mapped<T> implements Iterator<Row> {

        private final Iterator<? extends T> source;
        private final Function<T, Row> mapper;

        Mapped(Iterator<? extends T> source, Function<T, Row> mapper) {
            this.source = source;
            this.mapper = mapper;
        }

        public boolean hasNext() {
            return source.hasNext();
        }

        public Row next() {
            return mapper.apply(source.next());
        }
    }

    private static final class Rows implements Iterator<Map<String, Object>> {

        private final Iterator<Row> rows;
        private final SqlQuery query;
        private final Map<String, ?> parameters;
        private final Projector projector;
        private Map<String, Object> next;
        private int emitted;

        Rows(Iterator<Row> rows, SqlQuery query, Map<String, ?> parameters) {
            this.rows = rows;
            this.query = query;
            this.parameters = parameters;
            this.projector = new Projector(query);
            advance();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Map<String, Object> next() {
            if (next == null) throw new NoSuchElementException();
            Map<String, Object> current = next;
            advance();
            return current;
        }

        private void advance() {
            next = null;
            while (rows.hasNext() && emitted < query.limit()) if (match(rows.next())) return;
        }

        private boolean match(Row row) {
            if (!query.where().test(row, parameters)) return false;
            next = projector.projectRow(row);
            emitted++;
            return true;
        }
    }
}
