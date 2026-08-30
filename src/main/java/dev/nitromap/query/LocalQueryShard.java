package dev.nitromap.query;

import java.util.Iterator;
import java.util.Map;

final class LocalQueryShard implements QueryShard {

    private final Catalog catalog;
    private final QueryEngine queries;

    LocalQueryShard(Catalog catalog) {
        this.catalog = catalog;
        this.queries = new QueryEngine(catalog);
    }

    @Override
    public Iterable<DataRow> scan(Source source) {
        Table table = catalog.table(source.table());
        return () -> new Scanner(source, table);
    }

    @Override
    public DataRow lookup(Source source, Object key) {
        TableRow row = catalog.table(source.table()).find(key);
        return row == null ? null : DataRow.of(source, row);
    }

    @Override
    public Iterable<DataRow> project(String sql, Map<String, ?> parameters) {
        Iterable<Map<String, Object>> rows = queries.stream(sql, parameters);
        return () -> java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .map(DataRow::new).iterator();
    }

    private static final class Scanner implements Iterator<DataRow> {

        private final Source source;
        private final Table table;
        private final Iterator<? extends java.util.Map.Entry<?, ?>> entries;

        Scanner(Source source, Table table) {
            this.source = source;
            this.table = table;
            this.entries = table.entries().iterator();
        }

        @Override
        public boolean hasNext() {
            return entries.hasNext();
        }

        @Override
        public DataRow next() {
            return DataRow.of(source, table, entries.next());
        }
    }
}
