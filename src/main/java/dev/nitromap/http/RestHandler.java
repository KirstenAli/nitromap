package dev.nitromap.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.nitromap.query.QueryEngine;
import dev.nitromap.query.QueryResult;
import dev.nitromap.query.BinaryScalar;

import java.io.IOException;
import java.util.Map;

final class RestHandler implements HttpHandler {

    private final MapRegistry maps;
    private final QueryEngine queries;
    private final RequestAuthorizer authorizer;

    RestHandler(MapRegistry maps, QueryEngine queries,
                RequestAuthorizer authorizer) {
        this.maps = maps;
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
        else if (path.equals("/query")) query(exchange);
        else if (path.equals("/cluster/stream")) stream(exchange);
        else if (path.equals("/cluster/scan")) scan(exchange);
        else if (path.equals("/cluster/lookup")) lookup(exchange);
        else if (maps.route(exchange, path)) return;
        else HttpSupport.error(exchange, 404, "Not found");
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        HttpSupport.json(exchange, 200, maps.health());
    }

    private void query(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        if (queries == null) throw new IllegalArgumentException("Query engine is not configured");
        QueryResult result = queries.query(HttpSupport.text(exchange), HttpSupport.parameters(exchange));
        HttpSupport.json(exchange, 200, Map.of("rows", result.rows()));
    }

    private void stream(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        requireQueries();
        HttpSupport.rows(exchange, queries.stream(HttpSupport.text(exchange), HttpSupport.parameters(exchange)));
    }

    private void scan(HttpExchange exchange) throws IOException {
        if (!method(exchange, "GET")) return;
        requireQueries();
        Map<String, Object> values = HttpSupport.parameters(exchange);
        HttpSupport.rows(exchange, queries.scan(required(values, "table"), required(values, "alias")));
    }

    private void lookup(HttpExchange exchange) throws IOException {
        if (!method(exchange, "POST")) return;
        requireQueries();
        Map<String, Object> values = HttpSupport.parameters(exchange);
        Map<String, Object> row = queries.lookup(required(values, "table"),
                required(values, "alias"), scalar(exchange));
        HttpSupport.rows(exchange, row == null ? java.util.List.of() : java.util.List.of(row));
    }

    private Object scalar(HttpExchange exchange) throws IOException {
        try {
            return BinaryScalar.decode(HttpSupport.body(exchange));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid scalar key", exception);
        }
    }

    private void requireQueries() {
        if (queries == null) throw new IllegalArgumentException("Query engine is not configured");
    }

    private String required(Map<String, Object> values, String name) {
        if (!values.containsKey(name)) throw new IllegalArgumentException("Missing parameter: " + name);
        return String.valueOf(values.get(name));
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
