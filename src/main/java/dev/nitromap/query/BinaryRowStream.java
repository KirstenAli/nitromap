package dev.nitromap.query;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/** Framed binary rows with an explicit clean-stream terminator. */
public final class BinaryRowStream {

    private final RowCodec rows = new RowCodec();

    public void write(OutputStream output, Iterable<Map<String, Object>> values) throws IOException {
        DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(output));
        for (Map<String, Object> value : values) {
            stream.writeByte(1);
            rows.write(stream, new DataRow(value));
        }
        stream.writeByte(0);
        stream.flush();
    }

    public Iterable<Map<String, Object>> read(InputStream input) {
        DataInputStream stream = new DataInputStream(new BufferedInputStream(input));
        return () -> new Reader(stream, rows);
    }

    private static final class Reader implements Iterator<Map<String, Object>> {

        private final DataInputStream input;
        private final RowCodec codec;
        private DataRow next;

        Reader(DataInputStream input, RowCodec codec) {
            this.input = input;
            this.codec = codec;
            advance();
        }

        public boolean hasNext() {
            return next != null;
        }

        public Map<String, Object> next() {
            if (next == null) throw new NoSuchElementException();
            Map<String, Object> current = next.values();
            advance();
            return current;
        }

        private void advance() {
            try {
                int marker = input.read();
                if (marker < 0) throw new java.io.EOFException("Missing row-stream terminator");
                if (marker == 0) { next = null; input.close(); return; }
                if (marker != 1) throw new IOException("Invalid row-stream frame");
                next = codec.read(input);
                if (next == null) throw new java.io.EOFException("Incomplete row-stream frame");
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot read distributed row stream", exception);
            }
        }
    }
}
