package dev.nitromap.query;

import java.util.Objects;

final class Values {

    private Values() {
    }

    static boolean equal(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) return compare(left, right) == 0;
        return Objects.equals(left, right);
    }

    @SuppressWarnings("unchecked")
    static int compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b)
            return Double.compare(a.doubleValue(), b.doubleValue());
        if (left instanceof Comparable<?> value && left.getClass().isInstance(right))
            return ((Comparable<Object>) value).compareTo(right);
        throw new IllegalArgumentException("Values are not comparable");
    }

    static int compareNullable(Object left, Object right) {
        if (left == right) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return compare(left, right);
    }
}
