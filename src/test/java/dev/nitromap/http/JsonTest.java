package dev.nitromap.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void encodesNestedValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("ok", true);
        values.put("count", 2);
        values.put("items", Arrays.asList(null, "x"));
        assertEquals("{\"ok\":true,\"count\":2,\"items\":[null,\"x\"]}", json(values));
    }

    @Test
    void escapesStringsAndControlCharacters() {
        String value = "\"\\\b\f\n\r\t\u0001⚡";
        assertEquals("\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001⚡\"", json(value));
    }

    @Test
    void quotesUnknownValueTypes() {
        assertEquals("\"value\"", json(new StringBuilder("value")));
    }

    private String json(Object value) {
        return new String(Json.encode(value), StandardCharsets.UTF_8);
    }
}
