package dev.nitromap.cluster;

import dev.nitromap.NitroMap;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.http.NitroMapHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HttpClusterTransportTest {

    private final List<NitroMapHttpServer<?, ?>> servers = new ArrayList<>();
    private final List<NitroMap<String, String>> local = new ArrayList<>();
    private ClusterMap<String, String> cluster;

    @BeforeEach
    void setUp() throws Exception {
        List<ClusterNode> nodes = List.of(start("a"), start("b"));
        ClusterTopology topology = ClusterTopology.evenly(32, nodes);
        cluster = new ClusterMap<>("customers", Utf8StringCodec.INSTANCE, topology,
                new HttpClusterTransport<>(Utf8StringCodec.INSTANCE, Utf8StringCodec.INSTANCE));
    }

    @AfterEach
    void tearDown() throws Exception {
        servers.forEach(NitroMapHttpServer::close);
        for (NitroMap<String, String> map : local) map.close();
    }

    @Test
    void routesCrudOverExistingNamedMapEndpoints() {
        cluster.put("c1", "Ada");
        assertEquals("Ada", cluster.get("c1"));
        cluster.remove("c1");
        assertNull(cluster.get("c1"));
    }

    @Test
    void routesBatchesToTheirOwners() {
        Map<String, String> entries = Map.of("c1", "Ada", "c2", "Grace", "c3", "Linus");
        cluster.putAll(entries);
        entries.forEach((key, value) -> assertEquals(value, cluster.get(key)));
        cluster.removeAll(entries.keySet());
        entries.keySet().forEach(key -> assertNull(cluster.get(key)));
    }

    private ClusterNode start(String name) throws Exception {
        NitroMap<String, String> map = new NitroMap<>();
        NitroMapHttpServer<Object, Object> server = NitroMapHttpServer.builder()
                .map("customers", map, Utf8StringCodec.INSTANCE, Utf8StringCodec.INSTANCE)
                .port(0).threads(2).build().start();
        local.add(map);
        servers.add(server);
        return new ClusterNode(name, URI.create("http://127.0.0.1:" + server.port()));
    }
}
