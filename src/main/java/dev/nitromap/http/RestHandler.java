package dev.nitromap.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.nitromap.query.QueryEngine;
import dev.nitromap.query.QueryResult;

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
