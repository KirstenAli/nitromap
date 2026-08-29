package dev.nitromap.query;

import dev.nitromap.NitroMap;

import java.util.Map;

final class QueryFixture {

    private QueryFixture() {
    }

    static QueryEngine engine() {
        Catalog catalog = new Catalog()
                .add("customers", customers(), customerSchema())
                .add("orders", orders(), orderSchema());
        return new QueryEngine(catalog);
    }

    static QueryEngine emptyEngine() {
        Catalog catalog = new Catalog().add("customers", new NitroMap<String, Customer>(), customerSchema());
        return new QueryEngine(catalog);
    }

    private static Schema<Customer> customerSchema() {
        return Schema.<Customer>builder()
                .column("name", Customer::name)
                .column("city", Customer::city)
                .column("active", Customer::active)
                .column("score", Customer::score)
                .column("nickname", Customer::nickname)
                .build();
    }

    private static Schema<Order> orderSchema() {
        return Schema.<Order>builder()
                .column("customerId", Order::customerId)
                .column("total", Order::total)
                .build();
    }

    private static NitroMap<String, Customer> customers() {
        return new NitroMap<>(Map.of(
                "c1", new Customer("Alice", "London", true, 10, null),
                "c2", new Customer("Bob", "Paris", false, 20, null),
                "c3", new Customer("Cara", "London", true, 30, null),
                "c4", new Customer("Dan", "Rome", false, 40, "D'Angelo")));
    }

    private static NitroMap<String, Order> orders() {
        return new NitroMap<>(Map.of(
                "o1", new Order("c1", 120),
                "o2", new Order("c1", 30),
                "o3", new Order("c2", 200),
                "o4", new Order("c3", 5),
                "o5", new Order("c3", 0),
                "o6", new Order("c4", 300)));
    }

    record Customer(String name, String city, boolean active, int score, String nickname) {
    }

    record Order(String customerId, int total) {
    }
}
