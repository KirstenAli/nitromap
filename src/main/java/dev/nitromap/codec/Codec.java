package dev.nitromap.codec;

import java.io.IOException;

public interface Codec<T> {

    byte[] encode(T value) throws IOException;

    T decode(byte[] bytes) throws IOException;
}
