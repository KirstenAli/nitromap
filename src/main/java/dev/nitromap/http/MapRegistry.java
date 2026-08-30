package dev.nitromap.http;

import com.sun.net.httpserver.HttpExchange;
import dev.nitromap.NitroMap;
import dev.nitromap.codec.Codec;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

final class MapRegistry {

    private final Map<String, MapEndpoint<?, ?>> named;
    private MapEndpoint<?, ?> root;

    MapRegistry() {
        named = new LinkedHashMap<>();
    }

    private MapRegistry(MapEndpoint<?, ?> root,
                        Map<String, MapEndpoint<?, ?>> named) {
        this.root = root;
        this.named = named;
    }

    <K, V> void root(NitroMap<K, V> map, Codec<K> keys, Codec<V> values) {
        root = new MapEndpoint<>(map, keys, values);
    }

    <K, V> void add(String name, NitroMap<K, V> map,
                    Codec<K> keys, Codec<V> values) {
        validate(name);
        if (named.putIfAbsent(name, new MapEndpoint<>(map, keys, values)) != null)
            throw new IllegalArgumentException("Duplicate map: " + name);
    }

    boolean route(HttpExchange exchange, String path) throws IOException {
        if (root != null && root.route(exchange, path)) return true;
        return path.startsWith("/maps/") && named(exchange, path);
    }

    Map<String, Object> health() {
        if (named.isEmpty()) return Map.of("status", "ok", "size", root.size());
        return Map.of("status", "ok", "maps", count(), "size", size());
    }

    String rootPath(Object key) throws IOException {
        if (root == null) throw new IllegalStateException("No root map is configured");
        return root.entryPath(key);
    }

    String namedPath(String name, Object key) throws IOException {
        MapEndpoint<?, ?> endpoint = named.get(name);
        if (endpoint == null) throw new IllegalArgumentException("Unknown map: " + name);
        return "/maps/" + name + endpoint.entryPath(key);
    }

    MapRegistry snapshot() {
        requireMap();
        return new MapRegistry(root, Map.copyOf(named));
    }

    private boolean named(HttpExchange exchange, String path) throws IOException {
        int split = path.indexOf('/', 6);
        if (split < 0) return false;
        MapEndpoint<?, ?> endpoint = named.get(path.substring(6, split));
        return endpoint != null && endpoint.route(exchange, path.substring(split));
    }

    private int count() {
        return named.size() + (root == null ? 0 : 1);
    }

    private int size() {
        int size = root == null ? 0 : root.size();
        for (MapEndpoint<?, ?> endpoint : named.values()) size += endpoint.size();
        return size;
    }

    private void requireMap() {
        if (root == null && named.isEmpty())
            throw new IllegalStateException("At least one map is required");
    }

    private void validate(String name) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException("Invalid map name: " + name);
    }
}
