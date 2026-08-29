package dev.nitromap.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record QueryResult(List<Map<String, Object>> rows) {

    public QueryResult {
        List<Map<String, Object>> copy = new ArrayList<>(rows.size());
        rows.forEach(row -> copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(row))));
        rows = List.copyOf(copy);
    }

    public int size() {
        return rows.size();
    }
}
