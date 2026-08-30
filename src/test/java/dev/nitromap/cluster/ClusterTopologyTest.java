package dev.nitromap.cluster;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterTopologyTest {

    @Test
    void assignsAnyNodeCountEvenly() {
        ClusterTopology topology = ClusterTopology.evenly(8, nodes("a", "b", "c"));
        assertEquals(List.of("a", "b", "c", "a", "b", "c", "a", "b"), owners(topology));
    }

    @Test
    void reassignsOnlyRequestedPartitions() {
        ClusterTopology original = ClusterTopology.evenly(4, nodes("a", "b"));
        ClusterTopology changed = original.reassign(Map.of(1, "a"));
        assertEquals(List.of("a", "b", "a", "b"), owners(original));
        assertEquals(List.of("a", "a", "a", "b"), owners(changed));
    }

    @Test
    void rebalancesFixedPartitionsExplicitly() {
        ClusterTopology topology = ClusterTopology.evenly(4, nodes("a", "b"));
        ClusterTopology changed = topology.rebalance(nodes("a", "b", "c"));
        assertEquals(4, changed.partitions());
        assertEquals(List.of("a", "b", "c", "a"), owners(changed));
    }

    @Test
    void addsNodesWithoutMovingExistingPartitions() {
        ClusterTopology original = ClusterTopology.evenly(4, nodes("a", "b"));
        ClusterTopology expanded = original.withNodes(nodes("a", "b", "c"));
        assertEquals(original.owners(), expanded.owners());
        assertEquals("c", expanded.reassign(Map.of(3, "c")).owner(3).name());
    }

    @Test
    void restoresAnExplicitAssignment() {
        ClusterTopology topology = ClusterTopology.assigned(nodes("a", "b"),
                List.of("b", "b", "a", "a"));
        assertEquals(List.of("b", "b", "a", "a"), topology.owners());
    }

    @Test
    void rejectsInvalidTopologies() {
        assertThrows(IllegalArgumentException.class, () -> ClusterTopology.evenly(0, nodes("a")));
        assertThrows(IllegalArgumentException.class, () -> ClusterTopology.evenly(1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ClusterTopology.evenly(2, nodes("a", "a")));
    }

    @Test
    void rejectsUnknownAssignments() {
        ClusterTopology topology = ClusterTopology.evenly(2, nodes("a", "b"));
        assertThrows(IllegalArgumentException.class, () -> topology.reassign(Map.of(2, "a")));
        assertThrows(IllegalArgumentException.class, () -> topology.reassign(Map.of(1, "c")));
    }

    private List<String> owners(ClusterTopology topology) {
        return java.util.stream.IntStream.range(0, topology.partitions())
                .mapToObj(partition -> topology.owner(partition).name()).toList();
    }

    private List<ClusterNode> nodes(String... names) {
        return java.util.Arrays.stream(names).map(this::node).toList();
    }

    private ClusterNode node(String name) {
        return new ClusterNode(name, URI.create("http://" + name + ":8080"));
    }
}
