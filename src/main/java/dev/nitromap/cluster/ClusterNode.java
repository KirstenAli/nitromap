package dev.nitromap.cluster;

import java.net.URI;
import java.util.Objects;

/** A named NitroMap process that owns logical partitions. */
public record ClusterNode(String name, URI address) {

    public ClusterNode {
        if (name == null || !name.matches("[A-Za-z0-9._-]+"))
            throw new IllegalArgumentException("Invalid node name: " + name);
        Objects.requireNonNull(address, "address");
        String value = address.toString();
        if (value.endsWith("/")) address = URI.create(value.substring(0, value.length() - 1));
    }
}
