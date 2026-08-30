package dev.nitromap.query;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ScalarCodec {

    private ScalarCodec() {
    }

    static void write(DataOutput output, Object value) throws IOException {
        if (value == null) output.writeByte(0);
        else if (value instanceof String item) string(output, 1, item);
        else if (value instanceof Boolean item) bool(output, item);
        else if (value instanceof Byte item) number(output, 3, item);
        else if (value instanceof Short item) number(output, 4, item);
        else if (value instanceof Integer item) number(output, 5, item);
        else if (value instanceof Long item) number(output, 6, item);
        else if (value instanceof Float item) decimal(output, 7, item);
        else if (value instanceof Double item) decimal(output, 8, item);
        else if (value instanceof byte[] item) bytes(output, 9, item);
        else if (value instanceof Character item) string(output, 10, item.toString());
        else throw new IOException("Distributed SQL supports scalar values only: " + value.getClass().getName());
    }

    static Object read(DataInput input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> text(input);
            case 2 -> input.readBoolean();
            case 3 -> input.readByte();
            case 4 -> input.readShort();
            case 5 -> input.readInt();
            case 6 -> input.readLong();
            case 7 -> input.readFloat();
            case 8 -> input.readDouble();
            case 9 -> field(input);
            case 10 -> text(input).charAt(0);
            default -> throw new IOException("Unknown distributed scalar type");
        };
    }

    static byte[] bytes(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            write(new DataOutputStream(bytes), value);
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private static void bool(DataOutput output, boolean value) throws IOException {
        output.writeByte(2);
        output.writeBoolean(value);
    }

    private static void number(DataOutput output, int type, Number value) throws IOException {
        output.writeByte(type);
        if (type == 3) output.writeByte(value.byteValue());
        else if (type == 4) output.writeShort(value.shortValue());
        else if (type == 5) output.writeInt(value.intValue());
        else output.writeLong(value.longValue());
    }

    private static void decimal(DataOutput output, int type, Number value) throws IOException {
        output.writeByte(type);
        if (type == 7) output.writeFloat(value.floatValue());
        else output.writeDouble(value.doubleValue());
    }

    private static void string(DataOutput output, int type, String value) throws IOException {
        bytes(output, type, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(DataOutput output, int type, byte[] value) throws IOException {
        output.writeByte(type);
        output.writeInt(value.length);
        output.write(value);
    }

    private static String text(DataInput input) throws IOException {
        return new String(field(input), StandardCharsets.UTF_8);
    }

    private static byte[] field(DataInput input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 256 * 1024 * 1024) throw new IOException("Invalid scalar length");
        byte[] value = new byte[length];
        input.readFully(value);
        return value;
    }
}
