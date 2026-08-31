package com.sora.pjm.map;

import java.io.IOException;

/**
 * A chunk-independent source of PJM map tiles.
 *
 * <p>Implementations may read a static .pjmap file or generate tiles directly from the currently
 * loaded ProjectJapan runtime. The renderer does not care where the pixels came from.</p>
 */
public interface PJMMapSource extends AutoCloseable {
    PJMMapHeader header();

    /** Returns PNG bytes for a tile, or null when the tile is pure background/ocean. */
    byte[] readTile(PJMTileKey key) throws IOException;

    /** Human-readable description shown on the full-screen map. */
    String displayName();

    default boolean demo() {
        return false;
    }

    @Override
    default void close() throws IOException {
    }
}
