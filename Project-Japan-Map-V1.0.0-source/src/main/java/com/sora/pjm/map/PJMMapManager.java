package com.sora.pjm.map;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Chooses the base-map source.
 *
 * <p>If ProjectJapan is loaded, its runtime data always wins. A static projectjapan.pjmap remains
 * supported only as a standalone fallback when PJ itself is absent.</p>
 */
public final class PJMMapManager {
    private static final String STATIC_FILE = "projectjapan.pjmap";
    private static final String DEMO_RESOURCE = "/assets/pjm/pjmap/demo.pjmap";
    private static final String DEMO_FILE = "projectjapan-demo.pjmap";

    private PJMMapManager() {
    }

    public static MapSelection selectMap() throws IOException {
        if (PJMProjectJapanRuntime.isAvailable()) {
            try {
                PJMProjectJapanMapSource source = PJMProjectJapanMapSource.open();
                return new MapSelection(source, false, true);
            } catch (IOException runtimeFailure) {
                // Do not crash the client if a future PJ changes its API unexpectedly.
                return selectStandaloneFallback(
                        "ProjectJapan runtime adapter failed: " + runtimeFailure.getMessage());
            }
        }
        return selectStandaloneFallback("ProjectJapan is not loaded");
    }

    private static MapSelection selectStandaloneFallback(String reason) throws IOException {
        Path mapDir = FMLPaths.CONFIGDIR.get().resolve("PJM").resolve("maps");
        Files.createDirectories(mapDir);

        Path manual = mapDir.resolve(STATIC_FILE);
        if (Files.isRegularFile(manual)) {
            return new MapSelection(new PJMMapFile(manual), false, false);
        }

        Path demo = mapDir.resolve(DEMO_FILE);
        if (!Files.isRegularFile(demo)) {
            try (InputStream in = PJMMapManager.class.getResourceAsStream(DEMO_RESOURCE)) {
                if (in == null) {
                    throw new IOException("PJM demo base map resource is missing");
                }
                Files.copy(in, demo, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return new MapSelection(new DemoMapFile(demo, reason), true, false);
    }

    public record MapSelection(PJMMapSource source, boolean demo, boolean runtimeGenerated) {
    }

    private static final class DemoMapFile implements PJMMapSource {
        private final PJMMapFile delegate;
        private final String reason;

        private DemoMapFile(Path path, String reason) throws IOException {
            delegate = new PJMMapFile(path);
            this.reason = reason;
        }

        @Override
        public PJMMapHeader header() {
            return delegate.header();
        }

        @Override
        public byte[] readTile(PJMTileKey key) throws IOException {
            return delegate.readTile(key);
        }

        @Override
        public String displayName() {
            return "Demo map · " + reason;
        }

        @Override
        public boolean demo() {
            return true;
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
