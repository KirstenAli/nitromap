package dev.nitromap.http;

import dev.nitromap.NitroMap;
import dev.nitromap.codec.Codec;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.query.Catalog;
import dev.nitromap.query.QueryEngine;
import dev.nitromap.query.Schema;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NitroMapMultiMapHttpServerTest {

    private static final Utf8StringCodec UTF_8 = Utf8StringCodec.INSTANCE;
    private static final Codec<Integer> INTEGER = integerCodec();

    @TempDir
    Path directory;

    private NitroMap<String, String> customers;
    private NitroMap<String, Integer> counts;
    private NitroMapHttpServer<Object, Object> server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        customers = new NitroMap<>(directory.resolve("customers"), UTF_8, UTF_8);
        counts = new NitroMap<>(directory.resolve("counts"), UTF_8, INTEGER);
        client = HttpClient.newHttpClient();
        server = NitroMapHttpServer.builder()
                .map("customers", customers, UTF_8, UTF_8)
                .map("counts", counts, UTF_8, INTEGER)
                .queries(queries()).port(0).threads(2).build().start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        customers.close();
        counts.close();
    }

    @Test
    void routesEachMapWithItsCodec() throws Exception {
        request("PUT", entry("customers", "first"), bytes("Ada"));
        request("PUT", entry("counts", "active"), bytes("42"));
        assertEquals("Ada", customers.get("first"));
        assertEquals(42, counts.get("active"));
    }

    @Test
    void readsEntriesFromNamedMaps() throws Exception {
        customers.put("first", "Ada");
        counts.put("active", 42);
        assertEquals("Ada", text(request("GET", entry("customers", "first"))));
        assertEquals("42", text(request("GET", entry("counts", "active"))));
    }

    @Test
    void supportsNamedBatches() throws Exception {
        BinaryBatchCodec<String, String> codec = new BinaryBatchCodec<>(UTF_8, UTF_8);
        byte[] body = codec.encodeEntries(Map.of("first", "Ada", "second", "Grace"));
        assertEquals(204, request("PUT", "/maps/customers/entries", body).statusCode());
        assertEquals(2, customers.size());
    }

    @Test
    void exposesNamedPersistenceActions() throws Exception {
        customers.put("first", "Ada");
        assertEquals(204, request("POST", "/maps/customers/flush").statusCode());
        assertEquals(204, request("POST", "/maps/customers/compact").statusCode());
    }

    @Test
    void reportsCombinedHealth() throws Exception {
        customers.put("first", "Ada");
        counts.put("active", 42);
        String health = text(request("GET", "/health"));
        assertTrue(health.contains("\"maps\":2"));
        assertTrue(health.contains("\"size\":2"));
    }

    @Test
    void queriesAcrossNamedMaps() throws Exception {
        customers.put("first", "Ada");
        counts.put("first", 42);
        String result = text(request("POST", "/query", bytes(query())));
        assertTrue(result.contains("\"name\":\"Ada\""));
        assertTrue(result.contains("\"total\":42"));
    }

    @Test
    void aggregatesQueryResults() throws Exception {
        counts.putAll(Map.of("first", 10, "second", 20));
        String result = text(request("POST", "/query", bytes(aggregateQuery())));
        assertTrue(result.contains("\"sum\":30"));
        assertTrue(result.contains("\"avg\":15.0"));
    }

    @Test
    void returnsNotFoundForUnknownMaps() throws Exception {
        assertEquals(404, request("GET", "/maps/missing/entries/a2V5").statusCode());
    }

    @Test
    void requiresAtLeastOneMap() {
        assertThrows(IllegalStateException.class,
                () -> NitroMapHttpServer.builder().port(0).build());
    }

    @Test
    void validatesMapNames() {
        var builder = NitroMapHttpServer.builder()
                .map("customers", customers, UTF_8, UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> builder.map("bad/name", counts, UTF_8, INTEGER));
        assertThrows(IllegalArgumentException.class,
                () -> builder.map("customers", counts, UTF_8, INTEGER));
    }

    private String entry(String map, String key) throws Exception {
        return server.entryPath(map, key);
    }

    private QueryEngine queries() {
        return new QueryEngine(new Catalog()
                .add("customers", customers, customerSchema())
                .add("counts", counts, countSchema()));
    }

    private Schema<String> customerSchema() {
        return Schema.<String>builder().column("name", value -> value).build();
    }

    private Schema<Integer> countSchema() {
        return Schema.<Integer>builder().column("total", value -> value).build();
    }

    private String query() {
        return "SELECT c.name, n.total FROM customers c "
                + "INNER JOIN counts n ON c._key = n._key";
    }

    private String aggregateQuery() {
        return "SELECT SUM(n.total) AS sum, AVG(n.total) AS avg FROM counts n";
    }

    private HttpResponse<byte[]> request(String method, String path) throws Exception {
        return request(method, path, new byte[0]);
    }

    private HttpResponse<byte[]> request(String method, String path,
                                         byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Codec<Integer> integerCodec() {
        return new Codec<>() {
            public byte[] encode(Integer value) {
                return bytes(String.valueOf(value));
            }

            public Integer decode(byte[] value) {
                return Integer.valueOf(new String(value, StandardCharsets.UTF_8));
            }
        };
    }
}
