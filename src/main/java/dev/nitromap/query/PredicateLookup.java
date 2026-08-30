package dev.nitromap.query;

import java.util.Map;

record PredicateLookup(ColumnRef column, Object value) {

    static PredicateLookup find(SqlQuery query, Table table,
                                Map<String, ?> parameters) {
        return find(query.where(), query, table, parameters);
    }

    boolean key() {
        return "_key".equalsIgnoreCase(column.name());
    }

    private static PredicateLookup find(Condition condition, SqlQuery query,
                                        Table table, Map<String, ?> parameters) {
        if (condition instanceof Comparison comparison)
            return lookup(comparison, query, table, parameters);
        if (condition instanceof Logical logical && logical.operator() == LogicOperator.AND)
            return best(find(logical.left(), query, table, parameters),
                    find(logical.right(), query, table, parameters));
        return null;
    }

    private static PredicateLookup best(PredicateLookup left, PredicateLookup right) {
        if (left == null || right == null) return left == null ? right : left;
        return right.key() ? right : left;
    }

    private static PredicateLookup lookup(Comparison comparison, SqlQuery query,
                                          Table table, Map<String, ?> parameters) {
        if (comparison.operator() != CompareOperator.EQ) return null;
        PredicateLookup lookup = lookup(comparison.left(), comparison.right(),
                query, table, parameters);
        return lookup == null ? lookup(comparison.right(), comparison.left(),
                query, table, parameters) : lookup;
    }

    private static PredicateLookup lookup(Operand column, Operand value,
                                          SqlQuery query, Table table,
                                          Map<String, ?> parameters) {
        if (!(column instanceof ColumnRef reference) || !belongs(reference, query)) return null;
        Bound bound = bind(value, parameters);
        if (bound == null || !usable(reference, bound.value(), table)) return null;
        return new PredicateLookup(reference, bound.value());
    }

    private static Bound bind(Operand operand, Map<String, ?> parameters) {
        if (operand instanceof LiteralValue literal) return new Bound(literal.literal());
        if (operand instanceof ParameterValue parameter && parameters.containsKey(parameter.name()))
            return new Bound(parameters.get(parameter.name()));
        return null;
    }

    private static boolean belongs(ColumnRef column, SqlQuery query) {
        if (column.qualifier() == null) return query.joins().isEmpty();
        return column.qualifier().equalsIgnoreCase(query.from().alias());
    }

    private static boolean usable(ColumnRef column, Object value, Table table) {
        if ("_key".equalsIgnoreCase(column.name()))
            return value != null && !(value instanceof Number);
        return table.indexed(column.name());
    }

    private record Bound(Object value) {
    }
}
