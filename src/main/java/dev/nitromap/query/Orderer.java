package dev.nitromap.query;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class Orderer {

    private final SqlQuery query;

    Orderer(SqlQuery query) {
        this.query = query;
    }

    void sort(List<Map<String, Object>> rows) {
        Comparator<Map<String, Object>> comparator = null;
        for (OrderSpec order : query.orders()) comparator = append(comparator, order);
        if (comparator != null) rows.sort(comparator);
    }

    private Comparator<Map<String, Object>> append(
            Comparator<Map<String, Object>> current, OrderSpec order) {
        Comparator<Map<String, Object>> next = comparator(order);
        return current == null ? next : current.thenComparing(next);
    }

    private Comparator<Map<String, Object>> comparator(OrderSpec order) {
        String label = query.orderLabel(order.name());
        int direction = order.ascending() ? 1 : -1;
        return (left, right) -> direction * Values.compareNullable(left.get(label), right.get(label));
    }
}
