package dev.nitromap.http;

import com.sun.net.httpserver.HttpExchange;
import dev.nitromap.query.BinaryRowStream;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpSupport {

    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    private HttpSupport() {
    }

    static byte[] body(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Request body is too large");
        return body;
    }

    static String text(HttpExchange exchange) throws IOException {
        return new String(body(exchange), StandardCharsets.UTF_8);
    }

    static Map<String, Object> parameters(HttpExchange exchange) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null && !query.isBlank()) addParameters(parameters, query);
        return parameters;
    }

    static void json(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", Json.encode(value));
    }

    static void binary(HttpExchange exchange, byte[] value) throws IOException {
        send(exchange, 200, "application/octet-stream", value);
    }

    static void rows(HttpExchange exchange, Iterable<Map<String, Object>> rows) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/x-nitromap-rows");
        exchange.sendResponseHeaders(200, 0);
        new BinaryRowStream().write(exchange.getResponseBody(), rows);
    }

    static void empty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    static void error(HttpExchange exchange, int status, String message) throws IOException {
        json(exchange, status, Map.of("error", message));
    }

    private static void send(HttpExchange exchange, int status,
                             String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private static void addParameters(Map<String, Object> parameters, String query) {
        for (String pair : query.split("&")) addParameter(parameters, pair);
    }

    private static void addParameter(Map<String, Object> parameters, String pair) {
        String[] parts = pair.split("=", 2);
        String name = decode(parts[0]);
        String value = parts.length == 1 ? "" : decode(parts[1]);
        parameters.put(name, scalar(value));
    }

    private static Object scalar(String value) {
        if (value.equalsIgnoreCase("null")) return null;
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) return Boolean.valueOf(value);
        if (value.matches("-?\\d+")) return Long.valueOf(value);
        if (value.matches("-?\\d+\\.\\d+")) return Double.valueOf(value);
        return value;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
