package dev.nitromap.query;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

final class RowStore implements Iterable<DataRow>, AutoCloseable {

    private final Path directory;
    private final int memoryRows;
    private final RowCodec codec = new RowCodec();
    private final List<DataRow> memory = new ArrayList<>();
    private DataOutputStream output;
    private Path file;
    private long size;

    RowStore(Path directory, int memoryRows) {
        this.directory = directory;
        this.memoryRows = memoryRows;
    }

    void add(DataRow row) {
        try {
            if (output == null && memory.size() < memoryRows) memory.add(row);
            else write(row);
            size++;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot spill query rows", exception);
        }
    }

    long size() {
        return size;
    }

    boolean spilled() {
        return file != null;
    }

    void finish() {
        if (output == null) return;
        try {
            output.close();
            output = null;
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot finish query spill", exception);
        }
    }

    @Override
    public Iterator<DataRow> iterator() {
        finish();
        return file == null ? memory.iterator() : new FileIterator(file, codec);
    }

    @Override
    public void close() {
        finish();
        if (file == null) return;
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot delete query spill", exception);
        }
    }

    private void write(DataRow row) throws IOException {
        if (output == null) open();
        codec.write(output, row);
    }

    private void open() throws IOException {
        Files.createDirectories(directory);
        file = Files.createTempFile(directory, "nitromap-query-", ".rows");
        output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
        for (DataRow row : memory) codec.write(output, row);
        memory.clear();
    }

    private static final class FileIterator implements Iterator<DataRow> {

        private final DataInputStream input;
        private final RowCodec codec;
        private DataRow next;

        FileIterator(Path file, RowCodec codec) {
            this.codec = codec;
            this.input = input(file);
            advance();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public DataRow next() {
            if (next == null) throw new NoSuchElementException();
            DataRow current = next;
            advance();
            return current;
        }

        private void advance() {
            try {
                next = codec.read(input);
                if (next == null) input.close();
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot read query spill", exception);
            }
        }

        private static DataInputStream input(Path file) {
            try {
                return new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));
            } catch (IOException exception) {
                throw new UncheckedIOException("Cannot open query spill", exception);
            }
        }
    }
}
