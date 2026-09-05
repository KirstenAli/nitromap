package dev.nitromap.query;

import java.util.Collections;
import java.util.List;

interface AggregateState {

    void add(Object value);

    void merge(List<Object> partial);

    List<Object> partial();

    Object result();

    int width();

    static AggregateState create(AggregateFunction function) {
        return switch (function) {
            case COUNT -> new CountState();
            case SUM -> new SumState();
            case AVG -> new AverageState();
            case MIN -> new ExtremeState(true);
            case MAX -> new ExtremeState(false);
        };
    }
}

final class CountState implements AggregateState {

    private long count;

    public void add(Object value) {
        if (value != null) count++;
    }

    public void merge(List<Object> partial) {
        count = Math.addExact(count, ((Number) partial.get(0)).longValue());
    }

    public List<Object> partial() {
        return List.of(count);
    }

    public Object result() {
        return count;
    }

    public int width() {
        return 1;
    }
}

final class SumState implements AggregateState {

    private boolean seen;
    private boolean floating;
    private long integral;
    private double decimal;

    public void add(Object value) {
        if (value == null) return;
        Number number = AggregateNumbers.require(value, "SUM");
        if (floating || AggregateNumbers.floating(number)) addDecimal(number);
        else integral = Math.addExact(integral, number.longValue());
        seen = true;
    }

    public void merge(List<Object> partial) {
        if (!(Boolean) partial.get(0)) return;
        add((Boolean) partial.get(1) ? partial.get(3) : partial.get(2));
    }

    public List<Object> partial() {
        return List.of(seen, floating, integral, decimal);
    }

    public Object result() {
        if (!seen) return null;
        if (floating) return decimal;
        return integral;
    }

    public int width() {
        return 4;
    }

    private void addDecimal(Number value) {
        if (!floating) decimal = integral;
        floating = true;
        decimal += value.doubleValue();
    }
}

final class AverageState implements AggregateState {

    private double sum;
    private long count;

    public void add(Object value) {
        if (value == null) return;
        sum += AggregateNumbers.require(value, "AVG").doubleValue();
        count = Math.addExact(count, 1);
    }

    public void merge(List<Object> partial) {
        sum += ((Number) partial.get(0)).doubleValue();
        count = Math.addExact(count, ((Number) partial.get(1)).longValue());
    }

    public List<Object> partial() {
        return List.of(sum, count);
    }

    public Object result() {
        return count == 0 ? null : sum / count;
    }

    public int width() {
        return 2;
    }
}

final class ExtremeState implements AggregateState {

    private final boolean minimum;
    private Object current;

    ExtremeState(boolean minimum) {
        this.minimum = minimum;
    }

    public void add(Object value) {
        if (value != null && (current == null || better(value))) current = value;
    }

    public void merge(List<Object> partial) {
        add(partial.get(0));
    }

    public List<Object> partial() {
        return Collections.singletonList(current);
    }

    public Object result() {
        return current;
    }

    public int width() {
        return 1;
    }

    private boolean better(Object value) {
        int comparison = Values.compare(value, current);
        return minimum ? comparison < 0 : comparison > 0;
    }
}

final class AggregateNumbers {

    private AggregateNumbers() {
    }

    static Number require(Object value, String function) {
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float || value instanceof Double)
            return (Number) value;
        throw new IllegalArgumentException(function + " requires numeric values");
    }

    static boolean floating(Number value) {
        return value instanceof Float || value instanceof Double;
    }
}
