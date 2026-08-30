package dev.nitromap.cluster;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/** Data-plane operations used by a routed cluster map. */
public interface ClusterTransport<K, V> {

    V get(ClusterNode node, String map, K key) throws IOException;

    void put(ClusterNode node, String map, K key, V value) throws IOException;

    boolean remove(ClusterNode node, String map, K key) throws IOException;

    void putAll(ClusterNode node, String map, Map<K, V> entries) throws IOException;

    void removeAll(ClusterNode node, String map, Collection<K> keys) throws IOException;
}
