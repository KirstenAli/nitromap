package dev.nitromap.query;

import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class RowCodec {

    void write(DataOutput output, DataRow row) throws IOException {
        output.writeInt(row.values().size());
        for (var field : row.values().entrySet()) write(output, field);
    }

    DataRow read(DataInputStream input) throws IOException {
        Integer size = size(input);
        if (size == null) return null;
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) values.put(text(input), ScalarCodec.read(input));
        return new DataRow(values);
    }

    private void write(DataOutput output, Map.Entry<String, Object> field) throws IOException {
        byte[] name = field.getKey().getBytes(StandardCharsets.UTF_8);
        output.writeInt(name.length);
        output.write(name);
        ScalarCodec.write(output, field.getValue());
    }

    private Integer size(DataInputStream input) throws IOException {
        try {
            int size = input.readInt();
            if (size < 0 || size > 100_000) throw new IOException("Invalid row size");
            return size;
        } catch (EOFException end) {
            return null;
        }
    }

    private String text(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("Invalid field name");
        byte[] value = new byte[length];
        input.readFully(value);
        return new String(value, StandardCharsets.UTF_8);
    }
}
