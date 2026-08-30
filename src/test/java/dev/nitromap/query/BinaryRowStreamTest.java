package dev.nitromap.query;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinaryRowStreamTest {

    @Test
    void roundTripsRowsAndScalarTypes() throws Exception {
        Map<String, Object> row = values();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryRowStream().write(bytes, List.of(row, Map.of("name", "Grace")));
        assertEquals(List.of(row, Map.of("name", "Grace")), read(bytes));
    }

    @Test
    void roundTripsBinaryValues() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryRowStream().write(bytes, List.of(Map.of("bytes", new byte[]{1, 2, 3})));
        assertArrayEquals(new byte[]{1, 2, 3}, (byte[]) read(bytes).get(0).get("bytes"));
    }

    @Test
    void rejectsUnsupportedObjects() {
        assertThrows(java.io.IOException.class, () -> new BinaryRowStream()
                .write(new ByteArrayOutputStream(), List.of(Map.of("value", new Object()))));
    }

    @Test
    void reportsTruncatedRows() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryRowStream().write(bytes, List.of(Map.of("name", "Ada")));
        byte[] truncated = java.util.Arrays.copyOf(bytes.toByteArray(), bytes.size() - 1);
        assertThrows(UncheckedIOException.class, () -> new BinaryRowStream()
                .read(new ByteArrayInputStream(truncated)).forEach(row -> { }));
    }

    @Test
    void roundTripsAnEmptyStream() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryRowStream().write(bytes, List.of());
        assertEquals(List.of(), read(bytes));
    }

    @Test
    void rejectsTrailingScalarBytes() {
        byte[] encoded = BinaryScalar.encode("key");
        byte[] invalid = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(java.io.IOException.class, () -> BinaryScalar.decode(invalid));
    }

    private List<Map<String, Object>> read(ByteArrayOutputStream bytes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        new BinaryRowStream().read(new ByteArrayInputStream(bytes.toByteArray())).forEach(rows::add);
        return rows;
    }

    private Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("null", null);
        values.put("text", "Ada");
        values.put("boolean", true);
        values.put("byte", (byte) 1);
        values.put("short", (short) 2);
        values.put("integer", 3);
        values.put("long", 4L);
        values.put("float", 5.5f);
        values.put("double", 6.5d);
        values.put("character", 'x');
        return values;
    }
}
