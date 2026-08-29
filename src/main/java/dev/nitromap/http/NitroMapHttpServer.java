package dev.nitromap.http;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpServer;
import dev.nitromap.NitroMap;
import dev.nitromap.codec.Codec;
import dev.nitromap.query.QueryEngine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NitroMapHttpServer<K, V> implements AutoCloseable {

    private final Codec<K> keys;
    private final HttpServer server;
    private final HttpContext context;
    private final ExecutorService executor;

    private NitroMapHttpServer(Builder<K, V> builder) throws IOException {
        keys = builder.keys;
        server = HttpServer.create(builder.address, builder.backlog);
        executor = Executors.newFixedThreadPool(builder.threads);
        server.setExecutor(executor);
        context = server.createContext("/", builder.handler());
    }

    public static <K, V> Builder<K, V> builder(
            NitroMap<K, V> map, Codec<K> keys, Codec<V> values) {
        return new Builder<>(map, keys, values);
    }

    public NitroMapHttpServer<K, V> addFilter(Filter filter) {
        context.getFilters().add(filter);
        return this;
    }

    public NitroMapHttpServer<K, V> start() {
        server.start();
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String entryPath(K key) throws IOException {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(keys.encode(key));
        return "/entries/" + encoded;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    public static final class Builder<K, V> {

        private final NitroMap<K, V> map;
        private final Codec<K> keys;
        private final Codec<V> values;

        private InetSocketAddress address = new InetSocketAddress(8080);
        private RequestAuthorizer authorizer = RequestAuthorizer.ALLOW_ALL;
        private QueryEngine queries;
        private int backlog;
        private int threads = Math.max(2, Runtime.getRuntime().availableProcessors());

        private Builder(NitroMap<K, V> map, Codec<K> keys, Codec<V> values) {
            this.map = Objects.requireNonNull(map);
            this.keys = Objects.requireNonNull(keys);
            this.values = Objects.requireNonNull(values);
        }

        public Builder<K, V> port(int port) {
            address = new InetSocketAddress(port);
            return this;
        }

        public Builder<K, V> address(InetSocketAddress address) {
            this.address = Objects.requireNonNull(address);
            return this;
        }

        public Builder<K, V> backlog(int backlog) {
            this.backlog = backlog;
            return this;
        }

        public Builder<K, V> threads(int threads) {
            if (threads < 1) throw new IllegalArgumentException("threads must be positive");
            this.threads = threads;
            return this;
        }

        public Builder<K, V> queries(QueryEngine queries) {
            this.queries = Objects.requireNonNull(queries);
            return this;
        }

        public Builder<K, V> authorize(RequestAuthorizer authorizer) {
            this.authorizer = Objects.requireNonNull(authorizer);
            return this;
        }

        public NitroMapHttpServer<K, V> build() throws IOException {
            return new NitroMapHttpServer<>(this);
        }

        private RestHandler<K, V> handler() {
            return new RestHandler<>(map, keys, values, queries, authorizer);
        }
    }
}
