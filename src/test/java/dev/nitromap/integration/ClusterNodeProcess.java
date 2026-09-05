package dev.nitromap.integration;

import dev.nitromap.NitroMap;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.http.NitroMapHttpServer;
import dev.nitromap.query.Catalog;
import dev.nitromap.query.QueryEngine;
import dev.nitromap.query.Schema;

import java.nio.file.Path;
import java.util.Map;

final class ClusterNodeProcess {

    private static final Utf8StringCodec STRINGS = Utf8StringCodec.INSTANCE;

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        try (NitroMap<String, String> customers = map(directory, "customers");
             NitroMap<String, String> orders = map(directory, "orders");
             NitroMapHttpServer<?, ?> server = server(customers, orders)) {
            start(server);
            System.in.read();
        }
    }

    private static NitroMap<String, String> map(Path directory, String name) throws Exception {
        return new NitroMap<>(directory.resolve(name), STRINGS, STRINGS);
    }

    private static NitroMapHttpServer<?, ?> server(
            NitroMap<String, String> customers, NitroMap<String, String> orders) throws Exception {
        QueryEngine queries = new QueryEngine(catalog(customers, orders));
        return NitroMapHttpServer.builder().map("customers", customers, STRINGS, STRINGS)
                .map("orders", orders, STRINGS, STRINGS).clusterQueries(queries)
                .port(0).threads(2).build();
    }

    private static Catalog catalog(Map<String, String> customers, Map<String, String> orders) {
        return new Catalog().add("customers", customers, customerSchema())
                .add("orders", orders, orderSchema());
    }

    private static Schema<String> customerSchema() {
        return Schema.<String>builder().column("name", value -> part(value, 0))
                .column("city", value -> part(value, 1)).build();
    }

    private static Schema<String> orderSchema() {
        return Schema.<String>builder().column("customerId", value -> part(value, 0))
                .column("total", value -> Integer.parseInt(part(value, 1))).build();
    }

    private static String part(String value, int index) {
        return value.split("\\|", -1)[index];
    }

    private static void start(NitroMapHttpServer<?, ?> server) {
        server.start();
        System.out.println("READY " + server.port());
        System.out.flush();
    }
}
