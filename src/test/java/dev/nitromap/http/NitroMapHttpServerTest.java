package dev.nitromap.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import dev.nitromap.NitroMap;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.query.Catalog;
import dev.nitromap.query.QueryEngine;
import dev.nitromap.query.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NitroMapHttpServerTest {

    private static final Utf8StringCodec UTF_8 = Utf8StringCodec.INSTANCE;

    @TempDir
    Path directory;

    private BinaryBatchCodec<String, String> batches;
    private NitroMap<String, String> map;
    private NitroMapHttpServer<String, String> server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        batches = new BinaryBatchCodec<>(UTF_8, UTF_8);
        map = new NitroMap<>(directory, UTF_8, UTF_8);
        client = HttpClient.newHttpClient();
        server = server(RequestAuthorizer.ALLOW_ALL).start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
        map.close();
    }

    @Test
    void reportsHealthAndSize() throws Exception {
        map.put("key", "value");
        HttpResponse<byte[]> response = request("GET", "/health");
        assertEquals(200, response.statusCode());
        assertTrue(text(response).contains("\"size\":1"));
    }

    @Test
    void putsAndGetsEntries() throws Exception {
        assertEquals(204, request("PUT", entry("customer-1"), bytes("Ada")).statusCode());
        assertEquals("Ada", text(request("GET", entry("customer-1"))));
    }

    @Test
    void supportsUnicodeKeysAndValues() throws Exception {
        request("PUT", entry("顧客-1"), bytes("こんにちは 👋"));
        assertEquals("こんにちは 👋", text(request("GET", entry("顧客-1"))));
    }

    @Test
    void returnsNotFoundForMissingEntries() throws Exception {
        assertEquals(404, request("GET", entry("missing")).statusCode());
    }

    @Test
    void returnsNotFoundForUnknownRoutes() throws Exception {
        assertEquals(404, request("GET", "/missing").statusCode());
    }

    @Test
    void removesEntries() throws Exception {
        request("PUT", entry("customer-1"), bytes("Ada"));
        assertEquals(204, request("DELETE", entry("customer-1")).statusCode());
        assertEquals(404, request("GET", entry("customer-1")).statusCode());
    }

    @Test
    void returnsNotFoundWhenRemovingMissingEntries() throws Exception {
        assertEquals(404, request("DELETE", entry("missing")).statusCode());
    }

    @Test
    void putsEntriesInBulk() throws Exception {
        byte[] body = batches.encodeEntries(Map.of("first", "1", "second", "2"));
        assertEquals(204, request("PUT", "/entries", body).statusCode());
        assertEquals("2", text(request("GET", entry("second"))));
    }

    @Test
    void removesEntriesInBulk() throws Exception {
        map.putAll(Map.of("first", "1", "second", "2"));
        byte[] body = batches.encodeKeys(List.of("first", "second"));
        assertEquals(204, request("DELETE", "/entries", body).statusCode());
        assertEquals(0, map.size());
    }

    @Test
    void executesParameterizedQueries() throws Exception {
        map.putAll(Map.of("first", "Ada", "second", "Grace"));
        HttpResponse<byte[]> response = request("POST", queryPath("Ada"), bytes(query()));
        assertEquals(200, response.statusCode());
        assertTrue(text(response).contains("\"value\":\"Ada\""));
    }

    @Test
    void parsesScalarQueryParameters() throws Exception {
        map.put("first", "Ada");
        HttpResponse<byte[]> response = request("POST", scalarPath(), bytes(scalarQuery()));
        assertTrue(text(response).contains("\"value\":\"Ada\""));
    }

    @Test
    void escapesQueryResultsAsJson() throws Exception {
        map.put("first", "A\"da\n");
        HttpResponse<byte[]> response = request("POST", "/query", bytes("SELECT e.value FROM entries e"));
        assertTrue(text(response).contains("A\\\"da\\n"));
    }

    @Test
    void exposesFlushAndCompaction() throws Exception {
        map.put("key", "value");
        assertEquals(204, request("POST", "/flush").statusCode());
        assertEquals(204, request("POST", "/compact").statusCode());
    }

    @Test
    void rejectsUnsupportedMethods() throws Exception {
        HttpResponse<byte[]> response = request("POST", entry("key"));
        assertEquals(405, response.statusCode());
        assertEquals("GET, PUT, DELETE", response.headers().firstValue("Allow").orElseThrow());
    }

    @Test
    void rejectsInvalidBatchBodies() throws Exception {
        assertEquals(400, request("PUT", "/entries", new byte[]{0, 0}).statusCode());
    }

    @Test
    void rejectsInvalidEncodedKeys() throws Exception {
        assertEquals(400, request("GET", "/entries/!").statusCode());
    }

    @Test
    void rejectsQueriesWhenNotConfigured() throws Exception {
        restartWithoutQueries();
        assertEquals(400, request("POST", "/query", bytes("SELECT * FROM entries")).statusCode());
    }

    @Test
    void supportsAuthorizationHooks() throws Exception {
        restart(exchange -> "secret".equals(exchange.getRequestHeaders().getFirst("X-Api-Key")));
        HttpResponse<byte[]> denied = request("GET", "/health");
        assertEquals(401, denied.statusCode());
        assertEquals("NitroMap", denied.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(200, authorized("GET", "/health").statusCode());
    }

    @Test
    void reportsAuthorizationFailures() throws Exception {
        restart(exchange -> { throw new IOException("Auth failed"); });
        assertEquals(500, request("GET", "/health").statusCode());
    }

    @Test
    void runsNativeHttpFilters() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        restart(counter(calls));
        request("GET", "/health");
        assertEquals(1, calls.get());
    }

    @Test
    void validatesBuilderHooksAndThreadCounts() {
        var builder = NitroMapHttpServer.builder(map, UTF_8, UTF_8);
        assertThrows(IllegalArgumentException.class, () -> builder.threads(0));
        assertThrows(NullPointerException.class, () -> builder.authorize(null));
        assertThrows(NullPointerException.class, () -> builder.queries(null));
    }

    private void restart(RequestAuthorizer authorizer) throws Exception {
        server.close();
        server = server(authorizer).start();
    }

    private void restart(Filter filter) throws Exception {
        server.close();
        server = server(RequestAuthorizer.ALLOW_ALL);
        server.addFilter(filter).start();
    }

    private void restartWithoutQueries() throws Exception {
        server.close();
        server = NitroMapHttpServer.builder(map, UTF_8, UTF_8)
                .port(0).threads(2).build().start();
    }

    private NitroMapHttpServer<String, String> server(RequestAuthorizer authorizer) throws Exception {
        return NitroMapHttpServer.builder(map, UTF_8, UTF_8)
                .port(0).threads(2).queries(queries()).authorize(authorizer).build();
    }

    private QueryEngine queries() {
        Schema<String> schema = Schema.<String>builder().column("value", value -> value).build();
        return new QueryEngine(new Catalog().add("entries", map, schema));
    }

    private String query() {
        return "SELECT e._key, e.value FROM entries e WHERE e.value = :value";
    }

    private String queryPath(String value) {
        return "/query?value=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String scalarPath() {
        return "/query?flag=true&count=2&price=1.5&nothing=null";
    }

    private String scalarQuery() {
        return "SELECT e.value FROM entries e WHERE "
                + ":flag = true AND :count = 2 AND :price = 1.5 AND :nothing = NULL";
    }

    private String entry(String key) throws Exception {
        return server.entryPath(key);
    }

    private HttpResponse<byte[]> request(String method, String path) throws Exception {
        return request(method, path, new byte[0]);
    }

    private HttpResponse<byte[]> request(String method, String path, byte[] body) throws Exception {
        return send(method, path, body, null);
    }

    private HttpResponse<byte[]> authorized(String method, String path) throws Exception {
        return send(method, path, new byte[0], "secret");
    }

    private HttpResponse<byte[]> send(String method, String path, byte[] body,
                                      String apiKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        if (apiKey != null) request.header("X-Api-Key", apiKey);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.port() + path);
    }

    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private Filter counter(AtomicInteger calls) {
        return new Filter() {
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                calls.incrementAndGet();
                chain.doFilter(exchange);
            }

            public String description() {
                return "test counter";
            }
        };
    }
}
