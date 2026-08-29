package dev.nitromap.http;

import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Json {

    private Json() {
    }

    static byte[] encode(Object value) {
        StringBuilder json = new StringBuilder();
        append(json, value);
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void append(StringBuilder json, Object value) {
        if (value == null) json.append("null");
        else if (value instanceof Number || value instanceof Boolean) json.append(value);
        else if (value instanceof Map<?, ?> map) map(json, map);
        else if (value instanceof Iterable<?> values) array(json, values);
        else string(json, value.toString());
    }

    private static void map(StringBuilder json, Map<?, ?> values) {
        json.append('{');
        boolean comma = false;
        for (var entry : values.entrySet()) {
            if (comma) json.append(',');
            string(json, entry.getKey().toString());
            json.append(':');
            append(json, entry.getValue());
            comma = true;
        }
        json.append('}');
    }

    private static void array(StringBuilder json, Iterable<?> values) {
        json.append('[');
        boolean comma = false;
        for (Object value : values) {
            if (comma) json.append(',');
            append(json, value);
            comma = true;
        }
        json.append(']');
    }

    private static void string(StringBuilder json, String value) {
        json.append('"');
        value.codePoints().forEach(character -> escaped(json, character));
        json.append('"');
    }

    private static void escaped(StringBuilder json, int character) {
        switch (character) {
            case '"' -> json.append("\\\"");
            case '\\' -> json.append("\\\\");
            case '\b' -> json.append("\\b");
            case '\f' -> json.append("\\f");
            case '\n' -> json.append("\\n");
            case '\r' -> json.append("\\r");
            case '\t' -> json.append("\\t");
            default -> appendCharacter(json, character);
        }
    }

    private static void appendCharacter(StringBuilder json, int character) {
        if (character < 0x20) json.append(String.format("\\u%04x", character));
        else json.appendCodePoint(character);
    }
}
