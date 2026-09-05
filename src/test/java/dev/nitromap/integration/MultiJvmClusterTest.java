package dev.nitromap.integration;

import dev.nitromap.cluster.ClusterMap;
import dev.nitromap.cluster.ClusterNode;
import dev.nitromap.cluster.ClusterTopology;
import dev.nitromap.cluster.HttpClusterTransport;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.query.DistributedQueryEngine;
import dev.nitromap.query.DistributedQueryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class MultiJvmClusterTest {

    private static final Utf8StringCodec STRINGS = Utf8StringCodec.INSTANCE;

    @TempDir
    Path directory;

    private final List<NodeJvm> processes = new ArrayList<>();
    private ClusterMap<String, String> customers;
    private ClusterMap<String, String> orders;
    private DistributedQueryEngine queries;
    private Path spills;

    @BeforeEach
    void setUp() throws Exception {
        NodeJvm first = start("a");
        NodeJvm second = start("b");
        ClusterTopology topology = ClusterTopology.evenly(32, List.of(first.node(), second.node()));
        customers = map("customers", topology);
        orders = map("orders", topology);
        spills = directory.resolve("spills");
        queries = queries(first, second);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (int i = processes.size() - 1; i >= 0; i--) processes.get(i).close();
    }

    @Test
    void routesAndJoinsAcrossIndependentJvms() {
        CustomerKeys keys = routeJoinData();
        assertEquals("Ada|London", customers.get(keys.first()));
        assertEquals(joinedRows(), query(joinSql()));
    }

    @Test
    void isolatesNodeLossAndCleansFailedQuerySpills() throws Exception {
        CustomerKeys keys = routeFailureData();
        process("b").kill();
        assertEquals("Ada|London", customers.get(keys.first()));
        assertThrows(UncheckedIOException.class, () -> customers.get(keys.second()));
        assertThrows(UncheckedIOException.class, () -> query("SELECT c.name FROM customers c ORDER BY c.name"));
        assertEquals(0, spillFiles());
    }

    private CustomerKeys routeJoinData() {
        String first = key(customers, "a", "customer-a-");
        String second = key(customers, "b", "customer-b-");
        customers.putAll(Map.of(first, "Ada|London", second, "Grace|Paris"));
        routeOrders(first, second);
        return new CustomerKeys(first, second);
    }

    private void routeOrders(String first, String second) {
        orders.put(key(orders, "b", "order-b-"), first + "|10");
        orders.put(key(orders, "a", "order-a-"), second + "|20");
    }

    private CustomerKeys routeFailureData() {
        String first = key(customers, "a", "first-");
        String second = key(customers, "a", "second-");
        String lost = key(customers, "b", "lost-");
        customers.putAll(Map.of(first, "Ada|London", second, "Linus|Helsinki", lost, "Grace|Paris"));
        return new CustomerKeys(first, lost);
    }

    private NodeJvm start(String name) throws Exception {
        NodeJvm process = NodeJvm.start(name, directory.resolve(name));
        processes.add(process);
        return process;
    }

    private ClusterMap<String, String> map(String name, ClusterTopology topology) {
        return new ClusterMap<>(name, STRINGS, topology, new HttpClusterTransport<>(STRINGS, STRINGS));
    }

    private DistributedQueryEngine queries(NodeJvm first, NodeJvm second) {
        return DistributedQueryEngine.builder().node("a", first.node().address())
                .node("b", second.node().address()).shufflePartitions(2)
                .maxRowsInMemory(1).spillDirectory(spills).build();
    }

    private NodeJvm process(String name) {
        return processes.stream().filter(process -> process.node().name().equals(name)).findFirst().orElseThrow();
    }

    private String key(ClusterMap<String, String> map, String owner, String prefix) {
        for (int i = 0; i < 1_000; i++) if (map.owner(prefix + i).name().equals(owner)) return prefix + i;
        throw new IllegalStateException("No key found for " + owner);
    }

    private List<Map<String, Object>> query(String sql) {
        try (DistributedQueryResult result = queries.query(sql)) {
            return result.rows();
        }
    }

    private long spillFiles() throws Exception {
        if (Files.notExists(spills)) return 0;
        try (var files = Files.list(spills)) {
            return files.count();
        }
    }

    private String joinSql() {
        return "SELECT c.name, o.total FROM customers c JOIN orders o "
                + "ON c._key = o.customerId ORDER BY o.total";
    }

    private List<Map<String, Object>> joinedRows() {
        return List.of(Map.of("name", "Ada", "total", 10), Map.of("name", "Grace", "total", 20));
    }

    private record CustomerKeys(String first, String second) {
    }

    private record NodeJvm(Process process, ClusterNode node) implements AutoCloseable {

        static NodeJvm start(String name, Path directory) throws Exception {
            Process process = command(directory).redirectErrorStream(true).start();
            try {
                return connected(name, process, ready(process));
            } catch (Exception exception) {
                process.destroyForcibly();
                throw exception;
            }
        }

        void kill() throws Exception {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Child JVM did not stop");
        }

        @Override
        public void close() throws Exception {
            if (!process.isAlive()) return;
            process.getOutputStream().close();
            if (!process.waitFor(5, TimeUnit.SECONDS)) kill();
        }

        private static ProcessBuilder command(Path directory) {
            String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            return new ProcessBuilder(java, "-cp", classpath(), ClusterNodeProcess.class.getName(),
                    directory.toString());
        }

        private static String classpath() {
            return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        }

        private static String ready(Process process) throws Exception {
            return CompletableFuture.supplyAsync(() -> line(process)).get(10, TimeUnit.SECONDS);
        }

        private static NodeJvm connected(String name, Process process, String ready) {
            if (ready == null || !ready.startsWith("READY ")) return failed(process, ready);
            URI address = URI.create("http://127.0.0.1:" + ready.substring(6));
            return new NodeJvm(process, new ClusterNode(name, address));
        }

        private static String line(Process process) {
            try {
                return new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();
            } catch (Exception exception) {
                throw new IllegalStateException("Cannot read child JVM output", exception);
            }
        }

        private static NodeJvm failed(Process process, String output) {
            process.destroyForcibly();
            throw new IllegalStateException("Child JVM failed to start: " + output);
        }
    }
}
