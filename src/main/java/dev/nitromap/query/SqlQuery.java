package dev.nitromap.query;

import java.util.List;
import java.util.Map;

record SqlQuery(List<SelectItem> select, Source from, List<JoinSpec> joins,
                Condition where, List<ColumnRef> groups,
                List<OrderSpec> orders, int limit) {

    boolean grouped() {
        return !groups.isEmpty() || select.stream().anyMatch(SelectItem::aggregate);
    }

    boolean earlyLimit() {
        return limit < Integer.MAX_VALUE
                && streamable();
    }

    boolean streamable() {
        return joins.isEmpty() && !grouped() && orders.isEmpty();
    }

    String orderLabel(String requested) {
        return select.stream().filter(item -> item.matches(requested)).findFirst()
                .map(SelectItem::label).orElse(requested);
    }
}

record Source(String table, String alias) {
}

record JoinSpec(Source source, ColumnRef left, ColumnRef right) {
}

record SelectItem(SelectValue value, String alias) {

    String label() {
        if (alias != null) return alias;
        return value instanceof ColumnRef column ? column.name() : "count";
    }

    boolean aggregate() {
        return value instanceof CountAll;
    }

    boolean matches(String name) {
        if (alias != null && alias.equalsIgnoreCase(name)) return true;
        return value instanceof ColumnRef column && column.matches(name);
    }
}

sealed interface SelectValue permits ColumnRef, CountAll, Wildcard {
}

sealed interface Operand permits ColumnRef, LiteralValue, ParameterValue {

    Object value(ValueRow row, Map<String, ?> parameters);
}

record ColumnRef(String qualifier, String name) implements SelectValue, Operand {

    @Override
    public Object value(ValueRow row, Map<String, ?> parameters) {
        return row.read(this);
    }

    boolean matches(String value) {
        return name.equalsIgnoreCase(value) || qualified().equalsIgnoreCase(value);
    }

    String qualified() {
        return qualifier == null ? name : qualifier + "." + name;
    }
}

record CountAll() implements SelectValue {
}

record Wildcard() implements SelectValue {
}

record LiteralValue(Object literal) implements Operand {

    @Override
    public Object value(ValueRow row, Map<String, ?> parameters) {
        return literal;
    }
}

record ParameterValue(String name) implements Operand {

    @Override
    public Object value(ValueRow row, Map<String, ?> parameters) {
        if (!parameters.containsKey(name)) throw new IllegalArgumentException("Missing parameter: " + name);
        return parameters.get(name);
    }
}

sealed interface Condition permits Always, Comparison, Logical {

    boolean test(ValueRow row, Map<String, ?> parameters);
}

record Always() implements Condition {

    @Override
    public boolean test(ValueRow row, Map<String, ?> parameters) {
        return true;
    }
}

record Comparison(Operand left, CompareOperator operator, Operand right) implements Condition {

    @Override
    public boolean test(ValueRow row, Map<String, ?> parameters) {
        return operator.test(left.value(row, parameters), right.value(row, parameters));
    }
}

record Logical(Condition left, LogicOperator operator, Condition right) implements Condition {

    @Override
    public boolean test(ValueRow row, Map<String, ?> parameters) {
        return operator == LogicOperator.AND
                ? left.test(row, parameters) && right.test(row, parameters)
                : left.test(row, parameters) || right.test(row, parameters);
    }
}

enum LogicOperator {
    AND, OR
}

enum CompareOperator {
    EQ, NE, GT, GE, LT, LE;

    boolean test(Object left, Object right) {
        if (this == EQ) return Values.equal(left, right);
        if (this == NE) return !Values.equal(left, right);
        if (left == null || right == null) return false;
        return test(Values.compare(left, right));
    }

    private boolean test(int comparison) {
        return switch (this) {
            case GT -> comparison > 0;
            case GE -> comparison >= 0;
            case LT -> comparison < 0;
            case LE -> comparison <= 0;
            default -> throw new IllegalStateException("Not an ordering operator");
        };
    }
}

record OrderSpec(String name, boolean ascending) {
}
