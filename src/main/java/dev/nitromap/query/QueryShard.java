package dev.nitromap.query;

import java.util.Map;

interface QueryShard {

    Iterable<DataRow> scan(Source source);

    DataRow lookup(Source source, Object key);

    Iterable<DataRow> project(String sql, Map<String, ?> parameters);
}
