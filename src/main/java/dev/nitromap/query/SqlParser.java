package dev.nitromap.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SqlParser {

    private static final Set<String> RESERVED = Set.of(
            "INNER", "JOIN", "ON", "WHERE", "GROUP", "ORDER", "LIMIT", "ASC", "DESC");

    private final List<String> tokens;
    private int position;

    private SqlParser(String sql) {
        tokens = new Tokenizer(sql).tokenize();
    }

    static SqlQuery parse(String sql) {
        return new SqlParser(sql).parse();
    }

    private SqlQuery parse() {
        expect("SELECT");
        List<SelectItem> select = selectItems();
        expect("FROM");
        return parse(select, source());
    }

    private SqlQuery parse(List<SelectItem> select, Source from) {
        SqlQuery query = new SqlQuery(select, from, joins(), where(), groups(), orders(), limit());
        finish();
        return query;
    }

    private Condition where() {
        return match("WHERE") ? condition() : new Always();
    }

    private int limit() {
        return match("LIMIT") ? integer() : Integer.MAX_VALUE;
    }

    private List<SelectItem> selectItems() {
        List<SelectItem> items = new ArrayList<>();
        do items.add(selectItem()); while (match(","));
        return items;
    }

    private SelectItem selectItem() {
        SelectValue value = selectValue();
        String alias = match("AS") ? identifier() : null;
        return new SelectItem(value, alias);
    }

    private SelectValue selectValue() {
        if (match("*")) return new Wildcard();
        if (!match("COUNT")) return column(identifier());
        expect("(");
        expect("*");
        expect(")");
        return new CountAll();
    }

    private Source source() {
        String table = identifier();
        String alias = match("AS") ? identifier() : implicitAlias(table);
        return new Source(table, alias);
    }

    private String implicitAlias(String table) {
        return canBeAlias() ? next() : table;
    }

    private List<JoinSpec> joins() {
        List<JoinSpec> joins = new ArrayList<>();
        while (peek("JOIN") || peek("INNER")) joins.add(join());
        return joins;
    }

    private JoinSpec join() {
        match("INNER");
        expect("JOIN");
        Source source = source();
        expect("ON");
        ColumnRef left = column(identifier());
        expect("=");
        return new JoinSpec(source, left, column(identifier()));
    }

    private List<ColumnRef> groups() {
        if (!match("GROUP")) return List.of();
        expect("BY");
        return columns();
    }

    private List<OrderSpec> orders() {
        if (!match("ORDER")) return List.of();
        expect("BY");
        List<OrderSpec> orders = new ArrayList<>();
        do orders.add(order()); while (match(","));
        return orders;
    }

    private OrderSpec order() {
        String name = identifier();
        boolean ascending = !match("DESC");
        if (ascending) match("ASC");
        return new OrderSpec(name, ascending);
    }

    private List<ColumnRef> columns() {
        List<ColumnRef> columns = new ArrayList<>();
        do columns.add(column(identifier())); while (match(","));
        return columns;
    }

    private Condition condition() {
        return or();
    }

    private Condition or() {
        Condition condition = and();
        while (match("OR")) condition = new Logical(condition, LogicOperator.OR, and());
        return condition;
    }

    private Condition and() {
        Condition condition = primary();
        while (match("AND")) condition = new Logical(condition, LogicOperator.AND, primary());
        return condition;
    }

    private Condition primary() {
        if (!match("(")) return comparison();
        Condition condition = condition();
        expect(")");
        return condition;
    }

    private Condition comparison() {
        Operand left = operand();
        CompareOperator operator = compareOperator(next());
        return new Comparison(left, operator, operand());
    }

    private Operand operand() {
        String token = next();
        if (token.startsWith("'")) return new LiteralValue(string(token));
        if (token.startsWith(":")) return new ParameterValue(token.substring(1));
        return isLiteral(token) ? new LiteralValue(literal(token)) : column(token);
    }

    private boolean isLiteral(String token) {
        return token.equalsIgnoreCase("NULL") || token.equalsIgnoreCase("TRUE")
                || token.equalsIgnoreCase("FALSE") || token.matches("-?\\d+(\\.\\d+)?");
    }

    private Object literal(String token) {
        if (token.equalsIgnoreCase("NULL")) return null;
        if (token.equalsIgnoreCase("TRUE") || token.equalsIgnoreCase("FALSE")) return Boolean.valueOf(token);
        if (token.matches("-?\\d+")) return Long.valueOf(token);
        if (token.matches("-?\\d+\\.\\d+")) return Double.valueOf(token);
        return token;
    }

    private String string(String token) {
        return token.substring(1, token.length() - 1).replace("''", "'");
    }

    private CompareOperator compareOperator(String token) {
        return switch (token) {
            case "=" -> CompareOperator.EQ;
            case "!=", "<>" -> CompareOperator.NE;
            case ">" -> CompareOperator.GT;
            case ">=" -> CompareOperator.GE;
            case "<" -> CompareOperator.LT;
            case "<=" -> CompareOperator.LE;
            default -> throw error("Expected comparison operator");
        };
    }

    private ColumnRef column(String token) {
        int dot = token.lastIndexOf('.');
        return dot < 0 ? new ColumnRef(null, token)
                : new ColumnRef(token.substring(0, dot), token.substring(dot + 1));
    }

    private int integer() {
        int value = Integer.parseInt(next());
        if (value < 0) throw error("LIMIT cannot be negative");
        return value;
    }

    private boolean canBeAlias() {
        return position < tokens.size() && !RESERVED.contains(peek().toUpperCase())
                && !",);".contains(peek());
    }

    private String identifier() {
        String token = next();
        if (",()*;".contains(token)) throw error("Expected identifier");
        return token;
    }

    private void finish() {
        match(";");
        if (position != tokens.size()) throw error("Unexpected token: " + peek());
    }

    private void expect(String value) {
        if (!match(value)) throw error("Expected " + value);
    }

    private boolean match(String value) {
        if (!peek(value)) return false;
        position++;
        return true;
    }

    private boolean peek(String value) {
        return position < tokens.size() && tokens.get(position).equalsIgnoreCase(value);
    }

    private String peek() {
        return position < tokens.size() ? tokens.get(position) : "<end>";
    }

    private String next() {
        if (position >= tokens.size()) throw error("Unexpected end of query");
        return tokens.get(position++);
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at token " + position);
    }
}
