package dev.nitromap.cluster;

import dev.nitromap.codec.Codec;
import dev.nitromap.http.BinaryBatchCodec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;

/** Persistent-connection HTTP transport for routed map operations. */
public final class HttpClusterTransport<K, V> implements ClusterTransport<K, V> {

    private final HttpClient client;
    private final Codec<K> keys;
    private final Codec<V> values;
    private final BinaryBatchCodec<K, V> batches;
    private final Map<String, String> headers;

    public HttpClusterTransport(Codec<K> keys, Codec<V> values) {
        this(HttpClient.newHttpClient(), keys, values, Map.of());
    }

    public HttpClusterTransport(HttpClient client, Codec<K> keys, Codec<V> values,
                                Map<String, String> headers) {
        this.client = java.util.Objects.requireNonNull(client);
        this.keys = java.util.Objects.requireNonNull(keys);
        this.values = java.util.Objects.requireNonNull(values);
        this.batches = new BinaryBatchCodec<>(keys, values);
        this.headers = Map.copyOf(headers);
    }

    @Override
    public V get(ClusterNode node, String map, K key) throws IOException {
        HttpResponse<byte[]> response = send(node, entry(map, key), "GET", new byte[0]);
        if (response.statusCode() == 404) return null;
        require(response, 200);
        return values.decode(response.body());
    }

    @Override
    public void put(ClusterNode node, String map, K key, V value) throws IOException {
        require(send(node, entry(map, key), "PUT", values.encode(value)), 204);
    }

    @Override
    public boolean remove(ClusterNode node, String map, K key) throws IOException {
        HttpResponse<byte[]> response = send(node, entry(map, key), "DELETE", new byte[0]);
        if (response.statusCode() == 404) return false;
        require(response, 204);
        return true;
    }

    @Override
    public void putAll(ClusterNode node, String map, Map<K, V> entries) throws IOException {
        require(send(node, entries(map), "PUT", batches.encodeEntries(entries)), 204);
    }

    @Override
    public void removeAll(ClusterNode node, String map, Collection<K> keys) throws IOException {
        require(send(node, entries(map), "DELETE", batches.encodeKeys(keys)), 204);
    }

    private HttpResponse<byte[]> send(ClusterNode node, String path,
                                      String method, byte[] body) throws IOException {
        try {
            return client.send(request(node, path, method, body), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP request interrupted", exception);
        }
    }

    private HttpRequest request(ClusterNode node, String path, String method, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(node, path));
        headers.forEach(request::header);
        return request.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private URI uri(ClusterNode node, String path) {
        return URI.create(node.address() + path);
    }

    private String entry(String map, K key) throws IOException {
        return entries(map) + "/" + Base64.getUrlEncoder().withoutPadding().encodeToString(keys.encode(key));
    }

    private String entries(String map) {
        return "/maps/" + map + "/entries";
    }

    private void require(HttpResponse<byte[]> response, int status) throws IOException {
        if (response.statusCode() == status) return;
        throw new IOException("HTTP " + response.statusCode() + ": "
                + new String(response.body(), StandardCharsets.UTF_8));
    }
}
