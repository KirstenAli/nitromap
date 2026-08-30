package dev.nitromap.query;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/** Scalar wire encoding used by distributed direct-key lookups. */
public final class BinaryScalar {

    private BinaryScalar() {
    }

    public static byte[] encode(Object value) {
        return ScalarCodec.bytes(value);
    }

    public static Object decode(byte[] bytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        Object value = ScalarCodec.read(input);
        if (input.read() >= 0) throw new IOException("Trailing scalar bytes");
        return value;
    }
}
