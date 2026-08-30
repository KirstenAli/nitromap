package dev.nitromap.benchmark;

import dev.nitromap.NitroMap;
import dev.nitromap.codec.Utf8StringCodec;
import dev.nitromap.query.Catalog;
import dev.nitromap.query.QueryEngine;
import dev.nitromap.query.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag("benchmark")
class NitroMapBenchmarkTest {

    private static final Utf8StringCodec UTF_8 = Utf8StringCodec.INSTANCE;
    private static final int KEYS = 1 << 16;
    private static final int READS = 5_000_000;
    private static final int WRITES = 2_000_000;
    private static final int PERSISTENT_WRITES = 500_000;
    private static final int QUERY_ROWS = 10_000;
    private static final int QUERIES = 100;
    private static final int LOOKUP_ROWS = 50_000;
    private static final int LOOKUP_QUERIES = 5_000;
    private static final int SCAN_QUERIES = 100;
    private static final int INDEX_WRITES = 500_000;
    private static final int REPLAY_ROWS = 50_000;
    private static final int WARMUPS = 2;
    private static final int SAMPLES = 5;

    private static volatile long blackhole;

    @TempDir
    Path directory;

    @BeforeAll
    static void environment() {
        System.out.printf("%nNitroMap benchmarks: Java %s, %s %s, %d processors%n",
                Runtime.version(), property("os.name"), property("os.arch"), processors());
    }

    @Test
    void benchmarksInMemoryReads() throws Exception {
        NitroMap<Integer, Integer> map = integers();
        report(benchmark("In-memory get", "ops", READS, () -> reads(map)));
    }

    @Test
    void benchmarksInMemoryWrites() throws Exception {
        NitroMap<Integer, Integer> map = integers();
        report(benchmark("In-memory put", "ops", WRITES, () -> writes(map)));
    }

    @Test
    void benchmarksPersistentWrites() throws Exception {
        String[] keys = strings("key-");
        String[] values = strings("value-");
        try (NitroMap<String, String> map = persistentMap(directory)) {
            reportPersistent(persistentSamples(map, keys, values));
        }
    }

    @Test
    void benchmarksCachedQueries() throws Exception {
        QueryEngine engine = queryEngine();
        report(benchmark("Cached SQL query", "queries", QUERIES, () -> queries(engine)));
    }

    @Test
    void benchmarksQueryAccessPaths() throws Exception {
        QueryBench bench = queryBench();
        benchmarkKeyQueries(bench.direct());
        benchmarkIndexedQueries(bench.indexed());
        benchmarkScannedQueries(bench.scanned());
        benchmarkLimitedQueries(bench.direct());
        benchmarkPlainRowWrites(bench.plainRows(), bench.keys());
        benchmarkIndexedRowWrites(bench.indexedRows(), bench.keys());
    }

    @Test
    void benchmarksLogReplay() throws Exception {
        prepareReplayLog();
        report(benchmark("Log replay", "records", REPLAY_ROWS, () -> replay(directory)));
    }

    private Result benchmark(String name, String unit, long operations,
                             Task task) throws Exception {
        warm(task);
        long[] samples = samples(task);
        return new Result(name, unit, operations, median(samples));
    }

    private void warm(Task task) throws Exception {
        for (int run = 0; run < WARMUPS; run++) task.run();
    }

    private long[] samples(Task task) throws Exception {
        long[] samples = new long[SAMPLES];
        for (int run = 0; run < SAMPLES; run++) samples[run] = time(task);
        return samples;
    }

    private long time(Task task) throws Exception {
        long started = System.nanoTime();
        task.run();
        return System.nanoTime() - started;
    }

    private void report(Result result) {
        System.out.printf("%-24s %,14.0f %-10s %,10.1f ns/op%n",
                result.name(), result.rate(), result.unit() + "/s", result.cost());
    }

    private NitroMap<Integer, Integer> integers() {
        NitroMap<Integer, Integer> map = new NitroMap<>(KEYS);
        for (int key = 0; key < KEYS; key++) map.put(key, key);
        return map;
    }

    private void reads(NitroMap<Integer, Integer> map) {
        long total = 0;
        for (int index = 0; index < READS; index++) total += map.get(index & (KEYS - 1));
        blackhole = total;
    }

    private void writes(NitroMap<Integer, Integer> map) {
        for (int index = 0; index < WRITES; index++) map.put(index & (KEYS - 1), index);
        blackhole = map.get(0);
    }

    private List<PersistentSample> persistentSamples(
            NitroMap<String, String> map, String[] keys, String[] values) throws Exception {
        warmPersistent(map, keys, values);
        List<PersistentSample> samples = new ArrayList<>();
        for (int run = 0; run < SAMPLES; run++) samples.add(persistentSample(map, keys, values));
        return samples;
    }

    private void warmPersistent(NitroMap<String, String> map,
                                String[] keys, String[] values) throws Exception {
        for (int run = 0; run < WARMUPS; run++) persistentSample(map, keys, values);
    }

    private PersistentSample persistentSample(
            NitroMap<String, String> map, String[] keys, String[] values) throws Exception {
        long started = System.nanoTime();
        persistentWrites(map, keys, values);
        long enqueued = System.nanoTime() - started;
        map.flush();
        return new PersistentSample(enqueued, System.nanoTime() - started);
    }

    private void persistentWrites(NitroMap<String, String> map, String[] keys, String[] values) {
        for (int index = 0; index < PERSISTENT_WRITES; index++) {
            int key = index & (KEYS - 1);
            map.put(keys[key], values[key]);
        }
        blackhole = map.size();
    }

    private void reportPersistent(List<PersistentSample> samples) {
        report(result("Persistent put enqueue", samples.stream().mapToLong(PersistentSample::enqueued).toArray()));
        report(result("Put plus durability", samples.stream().mapToLong(PersistentSample::durable).toArray()));
    }

    private Result result(String name, long[] samples) {
        return new Result(name, "ops", PERSISTENT_WRITES, median(samples));
    }

    private QueryEngine queryEngine() {
        NitroMap<Integer, BenchRow> rows = new NitroMap<>(QUERY_ROWS);
        for (int key = 0; key < QUERY_ROWS; key++) rows.put(key, new BenchRow(key));
        Schema<BenchRow> schema = Schema.<BenchRow>builder().column("score", BenchRow::score).build();
        return new QueryEngine(new Catalog().add("rows", rows, schema));
    }

    private void queries(QueryEngine engine) {
        String sql = "SELECT r.score FROM rows r WHERE r.score >= :minimum "
                + "ORDER BY r.score DESC LIMIT 100";
        for (int run = 0; run < QUERIES; run++) blackhole += engine.query(sql, Map.of("minimum", 5_000)).size();
    }

    private QueryBench queryBench() {
        String[] keys = lookupKeys();
        NitroMap<String, BenchRow> rows = lookupRows(keys);
        NitroMap<String, BenchRow> indexedRows = lookupRows(keys);
        Schema<BenchRow> schema = lookupSchema();
        return new QueryBench(engine(rows, schema), indexed(indexedRows, schema),
                engine(rows, schema), rows, indexedRows, keys);
    }

    private NitroMap<String, BenchRow> lookupRows(String[] keys) {
        NitroMap<String, BenchRow> rows = new NitroMap<>(LOOKUP_ROWS);
        for (int key = 0; key < LOOKUP_ROWS; key++) rows.put(keys[key], new BenchRow(key));
        return rows;
    }

    private String[] lookupKeys() {
        String[] keys = new String[LOOKUP_ROWS];
        for (int key = 0; key < keys.length; key++) keys[key] = "key-" + key;
        return keys;
    }

    private Schema<BenchRow> lookupSchema() {
        return Schema.<BenchRow>builder().column("score", BenchRow::score).build();
    }

    private QueryEngine engine(NitroMap<String, BenchRow> rows, Schema<BenchRow> schema) {
        return new QueryEngine(new Catalog().add("rows", rows, schema));
    }

    private QueryEngine indexed(NitroMap<String, BenchRow> rows, Schema<BenchRow> schema) {
        return new QueryEngine(new Catalog().add("rows", rows, schema)
                .index("rows", "score"));
    }

    private void benchmarkKeyQueries(QueryEngine engine) throws Exception {
        report(benchmark("Direct _key query", "queries", LOOKUP_QUERIES,
                () -> keyQueries(engine)));
    }

    private void benchmarkIndexedQueries(QueryEngine engine) throws Exception {
        report(benchmark("Indexed query", "queries", LOOKUP_QUERIES,
                () -> valueQueries(engine, LOOKUP_QUERIES)));
    }

    private void benchmarkScannedQueries(QueryEngine engine) throws Exception {
        report(benchmark("Full-scan query", "queries", SCAN_QUERIES,
                () -> valueQueries(engine, SCAN_QUERIES)));
    }

    private void benchmarkLimitedQueries(QueryEngine engine) throws Exception {
        report(benchmark("Early LIMIT query", "queries", LOOKUP_QUERIES,
                () -> limitedQueries(engine)));
    }

    private void benchmarkPlainRowWrites(NitroMap<String, BenchRow> rows,
                                         String[] keys) throws Exception {
        report(benchmark("Plain row put", "ops", INDEX_WRITES,
                () -> rowWrites(rows, keys)));
    }

    private void benchmarkIndexedRowWrites(NitroMap<String, BenchRow> rows,
                                           String[] keys) throws Exception {
        report(benchmark("Secondary-indexed put", "ops", INDEX_WRITES,
                () -> rowWrites(rows, keys)));
    }

    private void keyQueries(QueryEngine engine) {
        String sql = "SELECT r.score FROM rows r WHERE r._key = :key";
        for (int run = 0; run < LOOKUP_QUERIES; run++)
            blackhole += engine.query(sql, Map.of("key", "key-" + run)).size();
    }

    private void valueQueries(QueryEngine engine, int count) {
        String sql = "SELECT r.score FROM rows r WHERE r.score = :score";
        for (int run = 0; run < count; run++)
            blackhole += engine.query(sql, Map.of("score", run)).size();
    }

    private void limitedQueries(QueryEngine engine) {
        String sql = "SELECT r.score FROM rows r LIMIT 10";
        for (int run = 0; run < LOOKUP_QUERIES; run++)
            blackhole += engine.query(sql).size();
    }

    private void rowWrites(NitroMap<String, BenchRow> rows, String[] keys) {
        for (int run = 0; run < INDEX_WRITES; run++) {
            int key = run % LOOKUP_ROWS;
            rows.put(keys[key], new BenchRow(run + LOOKUP_ROWS));
        }
        blackhole = rows.size();
    }

    private void prepareReplayLog() throws Exception {
        try (NitroMap<String, String> map = persistentMap(directory)) {
            map.putAll(entries(REPLAY_ROWS));
            map.flush();
            map.compact();
        }
    }

    private void replay(Path path) throws Exception {
        try (NitroMap<String, String> map = persistentMap(path)) {
            blackhole = map.size();
        }
    }

    private NitroMap<String, String> persistentMap(Path path) throws Exception {
        return new NitroMap<>(path, UTF_8, UTF_8);
    }

    private Map<String, String> entries(int count) {
        Map<String, String> entries = new HashMap<>(count);
        for (int key = 0; key < count; key++) entries.put("key-" + key, "value-" + key);
        return entries;
    }

    private String[] strings(String prefix) {
        String[] values = new String[KEYS];
        for (int key = 0; key < KEYS; key++) values[key] = prefix + key;
        return values;
    }

    private long median(long[] samples) {
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }

    private static String property(String name) {
        return System.getProperty(name);
    }

    private static int processors() {
        return Runtime.getRuntime().availableProcessors();
    }

    private record BenchRow(int score) {
    }

    private record QueryBench(QueryEngine direct, QueryEngine indexed, QueryEngine scanned,
                              NitroMap<String, BenchRow> plainRows,
                              NitroMap<String, BenchRow> indexedRows, String[] keys) {
    }

    private record PersistentSample(long enqueued, long durable) {
    }

    private record Result(String name, String unit, long operations, long nanos) {

        double rate() {
            return operations * 1_000_000_000d / nanos;
        }

        double cost() {
            return (double) nanos / operations;
        }
    }

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }
}
