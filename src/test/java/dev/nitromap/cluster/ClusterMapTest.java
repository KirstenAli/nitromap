package dev.nitromap.cluster;

import dev.nitromap.codec.Utf8StringCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterMapTest {

    private MemoryTransport transport;
    private ClusterMap<String, String> map;
    private ClusterTopology topology;

    @BeforeEach
    void setUp() {
        transport = new MemoryTransport();
        topology = ClusterTopology.evenly(32, nodes());
        map = new ClusterMap<>("customers", Utf8StringCodec.INSTANCE, topology, transport);
    }

    @Test
    void routesEachMutationToItsOnlyOwner() {
        map.put("c1", "Ada");
        ClusterNode owner = map.owner("c1");
        assertEquals("Ada", transport.data(owner).get("c1"));
        assertEquals(1, transport.totalSize());
    }

    @Test
    void readsAndRemovesFromTheSameOwner() {
        map.put("c1", "Ada");
        assertEquals("Ada", map.get("c1"));
        assertTrue(map.remove("c1"));
        assertFalse(map.remove("c1"));
    }

    @Test
    void batchesOncePerAffectedNode() {
        Map<String, String> entries = entriesAcrossNodes();
        map.putAll(entries);
        assertEquals(entries, transport.all());
        assertEquals(entries.size(), transport.batchedKeys);
        assertTrue(transport.batchCalls <= topology.nodes().size());
    }

    @Test
    void batchesRemovalsPerOwner() {
        Map<String, String> entries = entriesAcrossNodes();
        map.putAll(entries);
        map.removeAll(entries.keySet());
        assertEquals(0, transport.totalSize());
        assertTrue(transport.removeBatchCalls <= topology.nodes().size());
    }

    @Test
    void changesOwnersOnlyAfterAnExplicitTopologyUpdate() {
        String key = "c1";
        ClusterNode original = map.owner(key);
        String other = original.name().equals("a") ? "b" : "a";
        map.topology(topology.reassign(Map.of(map.partition(key), other)));
        assertEquals(other, map.owner(key).name());
    }

    @Test
    void neverFallsBackWhenAnOwnerFails() {
        transport.failed = map.owner("c1").name();
        UncheckedIOException error = assertThrows(UncheckedIOException.class, () -> map.get("c1"));
        assertTrue(error.getMessage().contains(transport.failed));
        assertEquals(1, transport.calls);
    }

    @Test
    void keepsLogicalPartitionCountFixed() {
        ClusterTopology other = ClusterTopology.evenly(16, nodes());
        assertThrows(IllegalArgumentException.class, () -> map.topology(other));
    }

    private Map<String, String> entriesAcrossNodes() {
        Map<String, String> entries = new HashMap<>();
        for (int i = 0; i < 100; i++) entries.put("key-" + i, "value-" + i);
        return entries;
    }

    private List<ClusterNode> nodes() {
        return List.of(node("a"), node("b"), node("c"));
    }

    private ClusterNode node(String name) {
        return new ClusterNode(name, URI.create("http://" + name));
    }

    private static final class MemoryTransport implements ClusterTransport<String, String> {

        private final Map<String, Map<String, String>> nodes = new HashMap<>();
        private int batchCalls;
        private int removeBatchCalls;
        private int batchedKeys;
        private int calls;
        private String failed;

        public String get(ClusterNode node, String map, String key) throws IOException {
            check(node);
            return data(node).get(key);
        }

        public void put(ClusterNode node, String map, String key, String value) throws IOException {
            check(node);
            data(node).put(key, value);
        }

        public boolean remove(ClusterNode node, String map, String key) throws IOException {
            check(node);
            return data(node).remove(key) != null;
        }

        public void putAll(ClusterNode node, String map, Map<String, String> entries) throws IOException {
            check(node);
            batchCalls++;
            batchedKeys += entries.size();
            data(node).putAll(entries);
        }

        public void removeAll(ClusterNode node, String map, Collection<String> keys) throws IOException {
            check(node);
            removeBatchCalls++;
            keys.forEach(data(node)::remove);
        }

        Map<String, String> data(ClusterNode node) {
            return nodes.computeIfAbsent(node.name(), ignored -> new HashMap<>());
        }

        Map<String, String> all() {
            Map<String, String> result = new HashMap<>();
            nodes.values().forEach(result::putAll);
            return result;
        }

        int totalSize() {
            return nodes.values().stream().mapToInt(Map::size).sum();
        }

        private void check(ClusterNode node) throws IOException {
            calls++;
            if (node.name().equals(failed)) throw new IOException("down");
        }
    }
}
