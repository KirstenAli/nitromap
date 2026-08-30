package dev.nitromap.query;

import java.util.Map;

final class DistributedPredicateLookup {

    private DistributedPredicateLookup() {
    }

    static Object key(SqlQuery query, Map<String, ?> parameters) {
        return key(query.where(), query.from().alias(), parameters);
    }

    private static Object key(Condition condition, String alias, Map<String, ?> parameters) {
        if (condition instanceof Comparison comparison) return key(comparison, alias, parameters);
        if (condition instanceof Logical logical && logical.operator() == LogicOperator.AND) {
            Object left = key(logical.left(), alias, parameters);
            return left == null ? key(logical.right(), alias, parameters) : left;
        }
        return null;
    }

    private static Object key(Comparison comparison, String alias,
                              Map<String, ?> parameters) {
        if (comparison.operator() != CompareOperator.EQ) return null;
        Object value = key(comparison.left(), comparison.right(), alias, parameters);
        return value == null ? key(comparison.right(), comparison.left(), alias, parameters) : value;
    }

    private static Object key(Operand column, Operand value, String alias,
                              Map<String, ?> parameters) {
        if (!(column instanceof ColumnRef reference) || !key(reference, alias)) return null;
        Object bound = bound(value, parameters);
        return bound instanceof Number ? null : bound;
    }

    private static boolean key(ColumnRef column, String alias) {
        return column.name().equalsIgnoreCase("_key")
                && (column.qualifier() == null || column.qualifier().equalsIgnoreCase(alias));
    }

    private static Object bound(Operand value, Map<String, ?> parameters) {
        if (value instanceof LiteralValue literal) return literal.literal();
        if (value instanceof ParameterValue parameter) return parameters.get(parameter.name());
        return null;
    }
}
