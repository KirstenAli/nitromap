package dev.nitromap.codec;

import java.nio.charset.StandardCharsets;

public final class Utf8StringCodec implements Codec<String> {

    public static final Utf8StringCodec INSTANCE = new Utf8StringCodec();

    private Utf8StringCodec() {
    }

    @Override
    public byte[] encode(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
