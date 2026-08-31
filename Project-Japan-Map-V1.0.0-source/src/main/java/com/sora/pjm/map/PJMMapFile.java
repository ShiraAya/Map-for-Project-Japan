package com.sora.pjm.map;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Random-access reader for PJM's chunk-independent .pjmap container. */
public final class PJMMapFile implements Closeable, PJMMapSource {
    private static final byte[] MAGIC = "PJMAP001".getBytes(StandardCharsets.US_ASCII);
    public static final int SUPPORTED_VERSION = 1;
    private static final int INDEX_ENTRY_BYTES = Long.BYTES + Integer.BYTES;

    private final RandomAccessFile file;
    private final PJMMapHeader header;
    private final String displayName;

    public PJMMapFile(Path path) throws IOException {
        this.file = new RandomAccessFile(path.toFile(), "r");
        this.displayName = path.getFileName().toString();
        this.header = readHeader();
        validateIndex();
    }

    @Override
    public PJMMapHeader header() {
        return header;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public synchronized byte[] readTile(PJMTileKey key) throws IOException {
        IndexEntry entry = readIndexEntry(key);
        if (entry == null || entry.length() <= 0) {
            return null;
        }
        if (entry.offset() < 0 || entry.offset() + entry.length() > header.indexOffset()) {
            throw new IOException("Corrupt PJMAP tile payload pointer for " + key);
        }
        byte[] bytes = new byte[entry.length()];
        file.seek(entry.offset());
        file.readFully(bytes);
        return bytes;
    }

    private IndexEntry readIndexEntry(PJMTileKey key) throws IOException {
        long ordinal = tileOrdinal(key.zoom(), key.x(), key.y());
        if (ordinal < 0 || ordinal >= Integer.toUnsignedLong(header.tileCount())) {
            return null;
        }
        long pointer = header.indexOffset() + ordinal * INDEX_ENTRY_BYTES;
        if (pointer < header.indexOffset() || pointer + INDEX_ENTRY_BYTES > file.length()) {
            throw new EOFException("PJMAP index entry points outside file");
        }
        file.seek(pointer);
        return new IndexEntry(file.readLong(), file.readInt());
    }

    private long tileOrdinal(int zoom, int x, int y) {
        if (zoom < header.minZoom() || zoom > header.maxZoom() || x < 0 || y < 0) {
            return -1;
        }
        long axis = 1L << zoom;
        if (x >= axis || y >= axis) {
            return -1;
        }
        long before = 0;
        for (int z = header.minZoom(); z < zoom; z++) {
            long a = 1L << z;
            before += a * a;
        }
        return before + y * axis + x;
    }

    private PJMMapHeader readHeader() throws IOException {
        byte[] magic = new byte[MAGIC.length];
        file.readFully(magic);
        for (int i = 0; i < MAGIC.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new IOException("Not a PJMAP file: invalid magic");
            }
        }

        int version = file.readInt();
        if (version != SUPPORTED_VERSION) {
            throw new IOException("Unsupported PJMAP version " + version + ", expected " + SUPPORTED_VERSION);
        }

        int tileSize = file.readInt();
        int minZoom = file.readInt();
        int maxZoom = file.readInt();
        double minWorldX = file.readDouble();
        double minWorldZ = file.readDouble();
        double maxWorldX = file.readDouble();
        double maxWorldZ = file.readDouble();
        long indexOffset = file.readLong();
        int tileCount = file.readInt();
        int backgroundArgb = file.readInt();

        if (tileSize <= 0 || minZoom < 0 || maxZoom < minZoom || maxZoom > 20 || tileCount < 0) {
            throw new IOException("Corrupt PJMAP header");
        }
        if (!(maxWorldX > minWorldX) || !(maxWorldZ > minWorldZ)) {
            throw new IOException("Invalid PJMAP world bounds");
        }

        return new PJMMapHeader(version, tileSize, minZoom, maxZoom,
                minWorldX, minWorldZ, maxWorldX, maxWorldZ,
                indexOffset, tileCount, backgroundArgb);
    }

    private void validateIndex() throws IOException {
        long expectedCount = 0;
        for (int z = header.minZoom(); z <= header.maxZoom(); z++) {
            long axis = 1L << z;
            expectedCount += axis * axis;
        }
        if (expectedCount != Integer.toUnsignedLong(header.tileCount())) {
            throw new IOException("PJMAP dense index tile count mismatch: expected " + expectedCount
                    + ", got " + Integer.toUnsignedLong(header.tileCount()));
        }
        long indexBytes = expectedCount * INDEX_ENTRY_BYTES;
        if (header.indexOffset() < 0 || header.indexOffset() + indexBytes > file.length()) {
            throw new EOFException("PJMAP index points outside file");
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    private record IndexEntry(long offset, int length) {
    }
}
