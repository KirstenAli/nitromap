package dev.nitromap.query;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class HttpQueryShard implements QueryShard {

    private final URI address;
    private final HttpClient client;
    private final Map<String, String> headers;
    private final BinaryRowStream rows = new BinaryRowStream();

    HttpQueryShard(URI address, HttpClient client, Map<String, String> headers) {
        this.address = normalize(address);
        this.client = client;
        this.headers = headers;
    }

    @Override
    public Iterable<DataRow> scan(Source source) {
        String query = "table=" + encode(source.table()) + "&alias=" + encode(source.alias());
        return read(send("/cluster/scan?" + query, "GET", new byte[0]));
    }

    @Override
    public DataRow lookup(Source source, Object key) {
        String query = "table=" + encode(source.table()) + "&alias=" + encode(source.alias());
        for (DataRow row : read(send("/cluster/lookup?" + query, "POST", BinaryScalar.encode(key)))) return row;
        return null;
    }

    @Override
    public Iterable<DataRow> project(String sql, Map<String, ?> parameters) {
        return read(send("/cluster/stream" + parameters(parameters), "POST",
                sql.getBytes(StandardCharsets.UTF_8)));
    }

    private Iterable<DataRow> read(InputStream input) {
        Iterable<Map<String, Object>> values = rows.read(input);
        return () -> java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .map(DataRow::new).iterator();
    }

    private InputStream send(String path, String method, byte[] body) {
        try {
            HttpResponse<InputStream> response = client.send(request(path, method, body),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) throw failure(response);
            return response.body();
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot reach distributed query node", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Distributed query interrupted", exception);
        }
    }

    private HttpRequest request(String path, String method, byte[] body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        headers.forEach(request::header);
        return request.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private IOException failure(HttpResponse<InputStream> response) throws IOException {
        String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        return new IOException("HTTP " + response.statusCode() + ": " + body);
    }

    private URI uri(String path) {
        return URI.create(address + path);
    }

    private String parameters(Map<String, ?> parameters) {
        if (parameters.isEmpty()) return "";
        return "?" + parameters.entrySet().stream().map(this::parameter)
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private String parameter(Map.Entry<String, ?> parameter) {
        return encode(parameter.getKey()) + "=" + encode(String.valueOf(parameter.getValue()));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private URI normalize(URI address) {
        String value = address.toString();
        return value.endsWith("/") ? URI.create(value.substring(0, value.length() - 1)) : address;
    }
}
