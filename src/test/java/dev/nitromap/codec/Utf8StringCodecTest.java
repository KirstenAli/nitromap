package dev.nitromap.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Utf8StringCodecTest {

    private final Utf8StringCodec codec = Utf8StringCodec.INSTANCE;

    @Test
    void roundTripsAscii() {
        assertRoundTrip("NitroMap");
    }

    @Test
    void roundTripsUnicode() {
        assertRoundTrip("Blazing ⚡ こんにちは");
    }

    @Test
    void roundTripsEmptyStrings() {
        assertRoundTrip("");
    }

    private void assertRoundTrip(String value) {
        assertEquals(value, codec.decode(codec.encode(value)));
    }
}
