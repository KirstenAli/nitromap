package dev.nitromap.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

@FunctionalInterface
public interface RequestAuthorizer {

    RequestAuthorizer ALLOW_ALL = exchange -> true;

    boolean authorize(HttpExchange exchange) throws IOException;
}
