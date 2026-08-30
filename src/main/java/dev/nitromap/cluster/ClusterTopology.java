package dev.nitromap.cluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable ownership for a fixed number of logical partitions. */
public final class ClusterTopology {

    private final List<ClusterNode> nodes;
    private final Map<String, ClusterNode> byName;
    private final String[] owners;

    private ClusterTopology(List<ClusterNode> nodes, String[] owners) {
        this.nodes = List.copyOf(nodes);
        this.byName = index(nodes);
        this.owners = owners.clone();
        validate();
    }

    public static ClusterTopology evenly(int partitions, List<ClusterNode> nodes) {
        requirePartitions(partitions);
        requireNodes(nodes);
        return new ClusterTopology(nodes, evenOwners(partitions, nodes));
    }

    public static ClusterTopology assigned(List<ClusterNode> nodes, List<String> owners) {
        if (owners == null || owners.isEmpty()) throw new IllegalArgumentException("At least one partition is required");
        requireNodes(nodes);
        return new ClusterTopology(nodes, owners.toArray(String[]::new));
    }

    public int partitions() {
        return owners.length;
    }

    public List<ClusterNode> nodes() {
        return nodes;
    }

    public List<String> owners() {
        return List.of(owners.clone());
    }

    public ClusterNode owner(int partition) {
        if (partition < 0 || partition >= owners.length) throw new IllegalArgumentException("Invalid partition");
        return byName.get(owners[partition]);
    }

    /** Returns a new topology; existing ownership changes only where requested. */
    public ClusterTopology reassign(Map<Integer, String> assignments) {
        String[] changed = owners.clone();
        assignments.forEach((partition, owner) -> assign(changed, partition, owner));
        return new ClusterTopology(nodes, changed);
    }

    /** Explicitly redistributes all fixed partitions over a new node list. */
    public ClusterTopology rebalance(List<ClusterNode> nodes) {
        return evenly(partitions(), nodes);
    }

    /** Changes membership while preserving every current partition owner. */
    public ClusterTopology withNodes(List<ClusterNode> nodes) {
        requireNodes(nodes);
        return new ClusterTopology(nodes, owners);
    }

    private void assign(String[] changed, int partition, String owner) {
        if (partition < 0 || partition >= changed.length) throw new IllegalArgumentException("Invalid partition");
        if (!byName.containsKey(owner)) throw new IllegalArgumentException("Unknown node: " + owner);
        changed[partition] = owner;
    }

    private void validate() {
        if (byName.size() != nodes.size()) throw new IllegalArgumentException("Duplicate node name");
        for (String owner : owners) if (!byName.containsKey(owner))
            throw new IllegalArgumentException("Unknown owner: " + owner);
    }

    private static Map<String, ClusterNode> index(List<ClusterNode> nodes) {
        Map<String, ClusterNode> result = new HashMap<>();
        nodes.forEach(node -> result.put(node.name(), node));
        return Map.copyOf(result);
    }

    private static String[] evenOwners(int partitions, List<ClusterNode> nodes) {
        String[] owners = new String[partitions];
        for (int i = 0; i < owners.length; i++) owners[i] = nodes.get(i % nodes.size()).name();
        return owners;
    }

    private static void requirePartitions(int partitions) {
        if (partitions < 1) throw new IllegalArgumentException("partitions must be positive");
    }

    private static void requireNodes(List<ClusterNode> nodes) {
        if (nodes == null || nodes.isEmpty()) throw new IllegalArgumentException("At least one node is required");
        if (nodes.stream().anyMatch(java.util.Objects::isNull)) throw new NullPointerException("node");
    }
}
