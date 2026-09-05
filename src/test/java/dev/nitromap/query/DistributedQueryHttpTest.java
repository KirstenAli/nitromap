package dev.nitromap.query;

import dev.nitromap.NitroMap;
import dev.nitromap.codec.Codec;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.http.NitroMapHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistributedQueryHttpTest {

    private static final Utf8StringCodec STRINGS = Utf8StringCodec.INSTANCE;
    private static final Codec<Customer> CUSTOMERS = customerCodec();
    private static final Codec<Order> ORDERS = orderCodec();

    @TempDir
    Path directory;

    private final List<NitroMap<?, ?>> maps = new ArrayList<>();
    private final List<NitroMapHttpServer<?, ?>> servers = new ArrayList<>();
    private DistributedQueryEngine engine;
    private URI first;

    @BeforeEach
    void setUp() throws Exception {
        first = start("a", Map.of("c1", new Customer("Ada", "London")),
                Map.of("o2", new Order("c2", 20)));
        URI second = start("b", Map.of("c2", new Customer("Grace", "Paris")),
                Map.of("o1", new Order("c1", 10)));
        engine = DistributedQueryEngine.builder().node("a", first).node("b", second)
                .header("X-Api-Key", "secret").shufflePartitions(2)
                .maxRowsInMemory(1).spillDirectory(directory.resolve("spill")).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        servers.forEach(NitroMapHttpServer::close);
        for (NitroMap<?, ?> map : maps) map.close();
    }

    @Test
    void streamsRemoteFiltersAndProjection() {
        String sql = "SELECT c.name FROM customers c WHERE c.city = 'London' LIMIT 1";
        assertEquals(List.of(Map.of("name", "Ada")), query(sql));
    }

    @Test
    void performsShuffleJoinsOverRemoteScans() {
        String sql = "SELECT c.name, o.total FROM customers c JOIN orders o "
                + "ON c._key = o.customerId ORDER BY o.total";
        assertEquals(List.of(Map.of("name", "Ada", "total", 10),
                Map.of("name", "Grace", "total", 20)), query(sql));
    }

    @Test
    void combinesRemotePartialGroups() {
        String sql = "SELECT c.city, COUNT(*) AS total FROM customers c GROUP BY c.city ORDER BY c.city";
        assertEquals(List.of(Map.of("city", "London", "total", 1L),
                Map.of("city", "Paris", "total", 1L)), query(sql));
    }

    @Test
    void combinesRemoteAggregateStates() {
        String sql = "SELECT COUNT(o.total) AS count, SUM(o.total) AS sum, AVG(o.total) AS avg, "
                + "MIN(o.total) AS min, MAX(o.total) AS max FROM orders o";
        assertEquals(Map.of("count", 2L, "sum", 30L, "avg", 15.0,
                "min", 10, "max", 20), query(sql).get(0));
    }

    @Test
    void usesRemoteDirectKeyLookups() {
        String sql = "SELECT c.name FROM customers c WHERE c._key = :key";
        assertEquals(List.of(Map.of("name", "Grace")), query(sql, Map.of("key", "c2")));
    }

    @Test
    void keepsOrdinaryQueriesDisabledOnClusterOnlyNodes() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(first.resolve("/query"))
                .header("X-Api-Key", "secret").POST(HttpRequest.BodyPublishers.ofString(
                        "SELECT c.name FROM customers c")).build();
        assertEquals(400, HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private URI start(String name, Map<String, Customer> customers,
                      Map<String, Order> orders) throws Exception {
        NitroMap<String, Customer> customerMap = new NitroMap<>(customers);
        NitroMap<String, Order> orderMap = new NitroMap<>(orders);
        QueryEngine queries = new QueryEngine(catalog(customerMap, orderMap));
        NitroMapHttpServer<Object, Object> server = NitroMapHttpServer.builder()
                .map("customers", customerMap, STRINGS, CUSTOMERS)
                .map("orders", orderMap, STRINGS, ORDERS).clusterQueries(queries)
                .authorize(exchange -> "secret".equals(exchange.getRequestHeaders().getFirst("X-Api-Key")))
                .port(0).threads(2).build().start();
        maps.add(customerMap);
        maps.add(orderMap);
        servers.add(server);
        return URI.create("http://127.0.0.1:" + server.port());
    }

    private Catalog catalog(NitroMap<String, Customer> customers,
                            NitroMap<String, Order> orders) {
        return new Catalog().add("customers", customers, customerSchema())
                .add("orders", orders, orderSchema());
    }

    private List<Map<String, Object>> query(String sql) {
        return query(sql, Map.of());
    }

    private List<Map<String, Object>> query(String sql, Map<String, ?> parameters) {
        try (DistributedQueryResult result = engine.query(sql, parameters)) {
            return result.rows();
        }
    }

    private Schema<Customer> customerSchema() {
        return Schema.<Customer>builder().column("name", Customer::name)
                .column("city", Customer::city).build();
    }

    private Schema<Order> orderSchema() {
        return Schema.<Order>builder().column("customerId", Order::customerId)
                .column("total", Order::total).build();
    }

    private static Codec<Customer> customerCodec() {
        return codec(value -> value.name() + "," + value.city(),
                value -> new Customer(value[0], value[1]));
    }

    private static Codec<Order> orderCodec() {
        return codec(value -> value.customerId() + "," + value.total(),
                value -> new Order(value[0], Integer.parseInt(value[1])));
    }

    private static <T> Codec<T> codec(java.util.function.Function<T, String> encode,
                                      java.util.function.Function<String[], T> decode) {
        return new Codec<>() {
            public byte[] encode(T value) { return encode.apply(value).getBytes(StandardCharsets.UTF_8); }
            public T decode(byte[] value) { return decode.apply(new String(value, StandardCharsets.UTF_8).split(",")); }
        };
    }

    private record Customer(String name, String city) {
    }

    private record Order(String customerId, int total) {
    }
}
