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
}
