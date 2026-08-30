package dev.nitromap.cluster;

import dev.nitromap.codec.Codec;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** A zero-replication client that routes each key to its sole owner. */
public final class ClusterMap<K, V> {

    private final String name;
    private final ClusterTransport<K, V> transport;
    private final KeyPartitioner<K> partitioner;
    private volatile ClusterTopology topology;

    public ClusterMap(String name, Codec<K> keys, ClusterTopology topology,
                      ClusterTransport<K, V> transport) {
        if (name == null || !name.matches("[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException("Invalid map name: " + name);
        this.name = name;
        this.topology = java.util.Objects.requireNonNull(topology);
        this.transport = java.util.Objects.requireNonNull(transport);
        this.partitioner = new KeyPartitioner<>(keys, topology.partitions());
    }

    public V get(K key) {
        ClusterNode node = node(key);
        try {
            return transport.get(node, name, key);
        } catch (IOException exception) {
            throw failure(node, exception);
        }
    }

    public void put(K key, V value) {
        ClusterNode node = node(key);
        try {
            transport.put(node, name, key, value);
        } catch (IOException exception) {
            throw failure(node, exception);
        }
    }

    public boolean remove(K key) {
        ClusterNode node = node(key);
        try {
            return transport.remove(node, name, key);
        } catch (IOException exception) {
            throw failure(node, exception);
        }
    }

    public void putAll(Map<K, V> entries) {
        groups(entries).forEach((node, batch) -> run(node, client -> client.putAll(node, name, batch)));
    }

    public void removeAll(Collection<K> keys) {
        keyGroups(keys).forEach((node, batch) -> run(node, client -> client.removeAll(node, name, batch)));
    }

    public int partition(K key) {
        return partitioner.partition(key);
    }

    public ClusterNode owner(K key) {
        return node(key);
    }

    /** Installs an explicit ownership update without moving or replicating data. */
    public void topology(ClusterTopology topology) {
        if (topology.partitions() != this.topology.partitions())
            throw new IllegalArgumentException("Logical partition count cannot change");
        this.topology = topology;
    }

    private ClusterNode node(K key) {
        return topology.owner(partition(key));
    }

    private Map<ClusterNode, Map<K, V>> groups(Map<K, V> entries) {
        ClusterTopology topology = this.topology;
        Map<ClusterNode, Map<K, V>> groups = new LinkedHashMap<>();
        entries.forEach((key, value) -> groups.computeIfAbsent(node(key, topology),
                ignored -> new LinkedHashMap<>()).put(key, value));
        return groups;
    }

    private Map<ClusterNode, Collection<K>> keyGroups(Collection<K> keys) {
        ClusterTopology topology = this.topology;
        Map<ClusterNode, Collection<K>> groups = new LinkedHashMap<>();
        keys.forEach(key -> groups.computeIfAbsent(node(key, topology), ignored -> new ArrayList<>()).add(key));
        return groups;
    }

    private ClusterNode node(K key, ClusterTopology topology) {
        return topology.owner(partition(key));
    }

    private void run(ClusterNode node, Action<K, V> action) {
        try {
            action.run(transport);
        } catch (IOException exception) {
            throw failure(node, exception);
        }
    }

    private UncheckedIOException failure(ClusterNode node, IOException exception) {
        return new UncheckedIOException("Cluster node unavailable: " + node.name(), exception);
    }

    @FunctionalInterface
    private interface Action<K, V> {
        void run(ClusterTransport<K, V> transport) throws IOException;
    }
}
