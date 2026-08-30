package dev.nitromap.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class QueryExecutor {

    private final Catalog catalog;
    private final SqlQuery query;
    private final Map<String, ?> parameters;

    QueryExecutor(Catalog catalog, SqlQuery query, Map<String, ?> parameters) {
        this.catalog = catalog;
        this.query = query;
        this.parameters = parameters;
    }

    QueryResult execute() {
        RowSource source = new RowSource(catalog, query, parameters);
        if (query.earlyLimit()) return early(source);
        List<Row> rows = source.rows();
        for (JoinSpec join : query.joins()) rows = new Joiner(catalog).join(rows, join);
        rows.removeIf(row -> !query.where().test(row, parameters));
        List<Map<String, Object>> result = new Projector(query).project(rows);
        new Orderer(query).sort(result);
        return new QueryResult(limit(result));
    }

    private QueryResult early(RowSource source) {
        List<Row> rows = source.matching(query.where(), parameters, query.limit());
        return new QueryResult(new Projector(query).project(rows));
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> rows) {
        if (rows.size() <= query.limit()) return rows;
        return new ArrayList<>(rows.subList(0, query.limit()));
    }
}
