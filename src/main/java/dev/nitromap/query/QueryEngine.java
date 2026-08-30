package dev.nitromap.query;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class QueryEngine {

    private final Catalog catalog;
    private final Map<String, SqlQuery> plans = new ConcurrentHashMap<>();

    public QueryEngine(Catalog catalog) {
        this.catalog = catalog;
    }

    public QueryResult query(String sql) {
        return query(sql, Map.of());
    }

    public QueryResult query(String sql, Map<String, ?> parameters) {
        SqlQuery plan = plans.computeIfAbsent(sql, SqlParser::parse);
        return new QueryExecutor(catalog, plan, parameters).execute();
    }

    /** Streams a scan/filter/projection query without materializing its rows. */
    public Iterable<Map<String, Object>> stream(String sql, Map<String, ?> parameters) {
        SqlQuery plan = plans.computeIfAbsent(sql, SqlParser::parse);
        return new QueryStream(catalog, plan, parameters);
    }

    /** Streams one table as qualified scalar fields for distributed operators. */
    public Iterable<Map<String, Object>> scan(String table, String alias) {
        Source source = new Source(table, alias);
        return () -> java.util.stream.StreamSupport.stream(
                new LocalQueryShard(catalog).scan(source).spliterator(), false)
                .map(DataRow::values).iterator();
    }

    /** Finds one table row by its map key for distributed direct predicates. */
    public Map<String, Object> lookup(String table, String alias, Object key) {
        DataRow row = new LocalQueryShard(catalog).lookup(new Source(table, alias), key);
        return row == null ? null : row.values();
    }
}
