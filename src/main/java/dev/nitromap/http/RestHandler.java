package dev.nitromap.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.nitromap.NitroMap;
import dev.nitromap.codec.Codec;
import dev.nitromap.query.QueryEngine;
import dev.nitromap.query.QueryResult;

import java.io.IOException;
import java.util.Collection;
import java.util.Base64;
import java.util.Map;

final class RestHandler<K, V> implements HttpHandler {

    private final NitroMap<K, V> map;
    private final Codec<K> keys;
    private final Codec<V> values;
    private final BinaryBatchCodec<K, V> batches;
    private final QueryEngine queries;
    private final RequestAuthorizer authorizer;

    RestHandler(NitroMap<K, V> map, Codec<K> keys, Codec<V> values,
                QueryEngine queries, RequestAuthorizer authorizer) {
        this.map = map;
        this.keys = keys;
        this.values = values;
        this.batches = new BinaryBatchCodec<>(keys, values);
        this.queries = queries;
        this.authorizer = authorizer;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            if (authorized(exchange)) route(exchange);
        } catch (IllegalArgumentException exception) {
            HttpSupport.error(exchange, 400, message(exception));
        } catch (Exception exception) {
            HttpSupport.error(exchange, 500, message(exception));
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        if (authorizer.authorize(exchange)) return true;
        exchange.getResponseHeaders().set("WWW-Authenticate", "NitroMap");
        HttpSupport.error(exchange, 401, "Unauthorized");
        return false;
    }

    private void route(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/health")) health(exchange);
        else if (path.equals("/flush")) flush(exchange);
        else if (path.equals("/compact")) compact(exchange);
        else if (path.equals("/query")) query(exchange);
        else if (path.equals("/entries")) entries(exchange);
        else if (path.startsWith("/entries/")) entry(exchange, path.substring(9));
        else HttpSupport.error(exchange, 404, "Not found");
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        HttpSupport.json(exchange, 200, Map.of("status", "ok", "size", map.size()));
    }

    private void flush(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        map.flush();
        HttpSupport.empty(exchange, 204);
    }

    private void compact(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        map.compact();
        HttpSupport.empty(exchange, 204);
    }

    private void query(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        if (queries == null) throw new IllegalArgumentException("Query engine is not configured");
        QueryResult result = queries.query(HttpSupport.text(exchange), HttpSupport.parameters(exchange));
        HttpSupport.json(exchange, 200, Map.of("rows", result.rows()));
    }

    private void entries(HttpExchange exchange) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "PUT" -> putAll(exchange);
            case "DELETE" -> removeAll(exchange);
            default -> methodNotAllowed(exchange, "PUT, DELETE");
        }
    }

    private void entry(HttpExchange exchange, String encodedKey) throws IOException {
        K key = key(encodedKey);
        switch (exchange.getRequestMethod()) {
            case "GET" -> get(exchange, key);
            case "PUT" -> put(exchange, key);
            case "DELETE" -> remove(exchange, key);
            default -> methodNotAllowed(exchange, "GET, PUT, DELETE");
        }
    }

    private void get(HttpExchange exchange, K key) throws IOException {
        V value = map.get(key);
        if (value == null) HttpSupport.error(exchange, 404, "Entry not found");
        else HttpSupport.binary(exchange, values.encode(value));
    }

    private void put(HttpExchange exchange, K key) throws IOException {
        map.put(key, value(HttpSupport.body(exchange)));
        HttpSupport.empty(exchange, 204);
    }

    private void remove(HttpExchange exchange, K key) throws IOException {
        if (map.remove(key) == null) HttpSupport.error(exchange, 404, "Entry not found");
        else HttpSupport.empty(exchange, 204);
    }

    private void putAll(HttpExchange exchange) throws IOException {
        map.putAll(entries(HttpSupport.body(exchange)));
        HttpSupport.empty(exchange, 204);
    }

    private void removeAll(HttpExchange exchange) throws IOException {
        map.removeAll(keys(HttpSupport.body(exchange)));
        HttpSupport.empty(exchange, 204);
    }

    private K key(String encoded) {
        try {
            return keys.decode(Base64.getUrlDecoder().decode(encoded));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid encoded key", exception);
        }
    }

    private V value(byte[] body) {
        try {
            return values.decode(body);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid encoded value", exception);
        }
    }

    private Map<K, V> entries(byte[] body) {
        try {
            return batches.decodeEntries(body);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid entry batch", exception);
        }
    }

    private Collection<K> keys(byte[] body) {
        try {
            return batches.decodeKeys(body);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid key batch", exception);
        }
    }

    private boolean method(HttpExchange exchange, String allowed) throws IOException {
        if (exchange.getRequestMethod().equals(allowed)) return true;
        methodNotAllowed(exchange, allowed);
        return false;
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) throws IOException {
        exchange.getResponseHeaders().set("Allow", allowed);
        HttpSupport.error(exchange, 405, "Method not allowed");
    }

    private String message(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
