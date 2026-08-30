package dev.nitromap.query;

import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded-memory SQL execution over shared-nothing NitroMap shards. */
public final class DistributedQueryEngine {

    private final List<QueryShard> shards;
    private final int shufflePartitions;
    private final int memoryRows;
    private final Path spillDirectory;
    private final Map<String, SqlQuery> plans = new ConcurrentHashMap<>();

    private DistributedQueryEngine(Builder builder) {
        shards = builder.shards();
        shufflePartitions = builder.shufflePartitions;
        memoryRows = builder.memoryRows;
        spillDirectory = builder.spillDirectory;
    }

    public static Builder builder() {
        return new Builder();
    }

    public DistributedQueryResult query(String sql) {
        return query(sql, Map.of());
    }

    public DistributedQueryResult query(String sql, Map<String, ?> parameters) {
        SqlQuery query = plans.computeIfAbsent(sql, SqlParser::parse);
        RowStore rows = new DistributedExecutor(shards, sql, query, parameters,
                shufflePartitions, memoryRows, spillDirectory).execute();
        return new DistributedQueryResult(rows);
    }

    public static final class Builder {

        private final List<NamedShard> nodes = new ArrayList<>();
        private final Map<String, String> headers = new java.util.LinkedHashMap<>();
        private HttpClient client = HttpClient.newHttpClient();
        private int shufflePartitions = 64;
        private int memoryRows = 10_000;
        private Path spillDirectory = Path.of(System.getProperty("java.io.tmpdir"), "nitromap-query");

        public Builder node(String name, Catalog catalog) {
            nodes.add(NamedShard.local(name, catalog));
            return this;
        }

        public Builder node(String name, URI address) {
            nodes.add(NamedShard.remote(name, address));
            return this;
        }

        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Builder httpClient(HttpClient client) {
            this.client = java.util.Objects.requireNonNull(client);
            return this;
        }

        public Builder shufflePartitions(int partitions) {
            if (partitions < 1) throw new IllegalArgumentException("shufflePartitions must be positive");
            shufflePartitions = partitions;
            return this;
        }

        public Builder maxRowsInMemory(int rows) {
            if (rows < 1) throw new IllegalArgumentException("maxRowsInMemory must be positive");
            memoryRows = rows;
            return this;
        }

        public Builder spillDirectory(Path directory) {
            spillDirectory = java.util.Objects.requireNonNull(directory);
            return this;
        }

        public DistributedQueryEngine build() {
            if (nodes.isEmpty()) throw new IllegalStateException("At least one query node is required");
            if (names().size() != nodes.size()) throw new IllegalStateException("Duplicate query node name");
            return new DistributedQueryEngine(this);
        }

        private List<QueryShard> shards() {
            return nodes.stream().map(node -> node.shard(client, Map.copyOf(headers))).toList();
        }

        private Set<String> names() {
            Set<String> names = new HashSet<>();
            nodes.forEach(node -> names.add(node.name().toLowerCase(java.util.Locale.ROOT)));
            return names;
        }
    }

    private record NamedShard(String name, Catalog catalog, URI address) {

        NamedShard {
            if (name == null || !name.matches("[A-Za-z0-9._-]+"))
                throw new IllegalArgumentException("Invalid query node name: " + name);
            if ((catalog == null) == (address == null)) throw new IllegalArgumentException("One node target is required");
        }

        static NamedShard local(String name, Catalog catalog) {
            return new NamedShard(name, java.util.Objects.requireNonNull(catalog), null);
        }

        static NamedShard remote(String name, URI address) {
            return new NamedShard(name, null, java.util.Objects.requireNonNull(address));
        }

        QueryShard shard(HttpClient client, Map<String, String> headers) {
            return catalog == null ? new HttpQueryShard(address, client, headers)
                    : new LocalQueryShard(catalog);
        }
    }
}
