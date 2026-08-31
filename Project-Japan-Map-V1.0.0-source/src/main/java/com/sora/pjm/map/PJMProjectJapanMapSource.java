package com.sora.pjm.map;

import net.minecraftforge.fml.loading.FMLPaths;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

/**
 * Runtime-generated map synchronized with the currently loaded ProjectJapan.
 *
 * <p>Rivers use the loaded PJ's final centre-lines. Lakes deliberately use only the loaded
 * ProjectJapan public water predicate ({@code LakeData.isLakeWaterWorld}) and never depend on a
 * private lake-shape representation.</p>
 */
public final class PJMProjectJapanMapSource implements PJMMapSource {
    private static final int TILE_SIZE = 256;
    private static final int MIN_ZOOM = 0;
    private static final int MAX_ZOOM = 12;

    /** Forces 0.7/0.8 river-corridor tiles to be discarded. */
    private static final String CACHE_RENDER_REVISION = "render-v6-runtime-lakes";

    private static final int OCEAN_ARGB = 0xFF6E97AD;
    private static final int RIVER_ARGB = 0xFF4E9FC8;
    private static final int LAKE_ARGB = 0xFF5B9DBB;

    private final PJMProjectJapanRuntime runtime;
    private final PJMMapHeader header;
    private final Path cacheDir;
    private final String displayName;

    public static PJMProjectJapanMapSource open() throws IOException {
        if (!PJMProjectJapanRuntime.isAvailable()) {
            throw new IOException("ProjectJapan is not loaded");
        }
        try {
            return new PJMProjectJapanMapSource(new PJMProjectJapanRuntime());
        } catch (ReflectiveOperationException e) {
            throw new IOException(
                    "Loaded ProjectJapan does not expose the PJM runtime sampling API", e);
        }
    }

    private PJMProjectJapanMapSource(PJMProjectJapanRuntime runtime) throws IOException {
        this.runtime = runtime;

        int minX = Math.min(runtime.minWorldX(), runtime.maxWorldX());
        int maxX = Math.max(runtime.minWorldX(), runtime.maxWorldX());
        int minZ = Math.min(runtime.minWorldZ(), runtime.maxWorldZ());
        int maxZ = Math.max(runtime.minWorldZ(), runtime.maxWorldZ());

        header = new PJMMapHeader(1, TILE_SIZE, MIN_ZOOM, MAX_ZOOM,
                minX, minZ, maxX, maxZ, 0L, 0, OCEAN_ARGB);
        displayName = "ProjectJapan " + runtime.version() + " · 自动生成";

        Path root = FMLPaths.CONFIGDIR.get()
                .resolve("PJM").resolve("cache").resolve("projectjapan");
        cacheDir = root.resolve(CACHE_RENDER_REVISION + "-" + runtime.fingerprint());
        Files.createDirectories(cacheDir);
        cleanupOldAutomaticCaches(root, cacheDir);
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
    public byte[] readTile(PJMTileKey key) throws IOException {
        if (key.zoom() < MIN_ZOOM || key.zoom() > MAX_ZOOM) return null;
        int axis = 1 << key.zoom();
        if (key.x() < 0 || key.y() < 0 || key.x() >= axis || key.y() >= axis) return null;

        Path png = tilePath(key, ".png");
        Path empty = tilePath(key, ".empty");

        if (Files.isRegularFile(png)) return Files.readAllBytes(png);
        if (Files.isRegularFile(empty)) return null;

        byte[] generated = generateTile(key);
        Files.createDirectories(png.getParent());

        if (generated == null) {
            try {
                Files.createFile(empty);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
            }
            return null;
        }

        Path temp = png.resolveSibling(png.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.write(temp, generated);
        try {
            Files.move(temp, png,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temp, png, StandardCopyOption.REPLACE_EXISTING);
        }
        return generated;
    }

    private byte[] generateTile(PJMTileKey key) throws IOException {
        BufferedImage image =
                new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        boolean[] landMask = new boolean[TILE_SIZE * TILE_SIZE];

        int axis = 1 << key.zoom();
        double mapPixels = TILE_SIZE * (double) axis;
        double worldPerPixelX = header.worldWidth() / mapPixels;
        double worldPerPixelZ = header.worldHeight() / mapPixels;
        long globalPixelX = (long) key.x() * TILE_SIZE;
        long globalPixelY = (long) key.y() * TILE_SIZE;

        boolean anyLand = false;

        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                int idx = py * TILE_SIZE + px;
                VisualAggregate terrain = key.zoom() <= 2
                        ? sampleOverview(globalPixelX + px, globalPixelY + py,
                                worldPerPixelX, worldPerPixelZ)
                        : sampleCentre(globalPixelX + px, globalPixelY + py,
                                worldPerPixelX, worldPerPixelZ);

                int argb = OCEAN_ARGB;
                if (terrain.landFraction() > 0.0D) {
                    argb = terrainColor(terrain.elevationMetres());
                    landMask[idx] = true;
                    anyLand = true;

                    if (key.zoom() <= 2) {
                        // Preserve tiny islands at nationwide zoom, without creating a huge
                        // coastline blur at local zoom.
                        argb = blend(OCEAN_ARGB, argb,
                                Math.max(0.38D, terrain.landFraction()));
                    } else if (key.zoom() <= 5 && terrain.coverage() < 0.98D) {
                        argb = blend(OCEAN_ARGB, argb, clamp01(terrain.coverage()));
                    }
                }
                image.setRGB(px, py, argb);
            }
        }

        // Lake pixels come ONLY from the current Forge-loaded ProjectJapan LakeData runtime API.
        // No private lake geometry is copied or interpreted by PJM.
        boolean anyLake = renderRuntimeLakes(image, key, globalPixelX, globalPixelY,
                worldPerPixelX, worldPerPixelZ);

        // A pure ocean tile may still contain the very end of a river mouth. We intentionally do
        // not save that as a blue-on-blue offshore line: map rivers terminate visually at the
        // coastline, while PJ's offshore extension remains an internal terrain-generation detail.
        if (!anyLand && !anyLake) return null;

        renderVectorRivers(image, landMask, key, globalPixelX, globalPixelY,
                worldPerPixelX, worldPerPixelZ);

        ByteArrayOutputStream out = new ByteArrayOutputStream(24 * 1024);
        if (!ImageIO.write(image, "PNG", out)) {
            throw new IOException("No PNG writer available");
        }
        return out.toByteArray();
    }

    private VisualAggregate sampleOverview(long globalPx, long globalPy,
                                           double worldPerPixelX, double worldPerPixelZ) {
        double[] offsets = {0.25D, 0.75D};
        int landSamples = 0;
        double elevation = 0.0D;
        double coverage = 0.0D;

        for (double oy : offsets) {
            for (double ox : offsets) {
                double worldX = header.minWorldX() + (globalPx + ox) * worldPerPixelX;
                double worldZ = header.minWorldZ() + (globalPy + oy) * worldPerPixelZ;
                double u = (worldX - header.minWorldX()) / header.worldWidth();
                double v = (worldZ - header.minWorldZ()) / header.worldHeight();
                PJMProjectJapanRuntime.VisualTerrain t = runtime.visualTerrain(u, v);
                coverage += t.coverage();
                if (t.land()) {
                    landSamples++;
                    elevation += t.elevationMetres();
                }
            }
        }

        return new VisualAggregate(
                landSamples / 4.0D,
                landSamples == 0 ? 0 : (int)Math.round(elevation / landSamples),
                coverage / 4.0D);
    }

    private VisualAggregate sampleCentre(long globalPx, long globalPy,
                                         double worldPerPixelX, double worldPerPixelZ) {
        double worldX = header.minWorldX() + (globalPx + 0.5D) * worldPerPixelX;
        double worldZ = header.minWorldZ() + (globalPy + 0.5D) * worldPerPixelZ;
        double u = (worldX - header.minWorldX()) / header.worldWidth();
        double v = (worldZ - header.minWorldZ()) / header.worldHeight();
        PJMProjectJapanRuntime.VisualTerrain t = runtime.visualTerrain(u, v);
        return new VisualAggregate(t.land() ? 1.0D : 0.0D,
                t.elevationMetres(), t.coverage());
    }

    /**
     * Samples the current ProjectJapan LakeData water result directly.
     *
     * <p>At z0-z3 one output pixel covers a very large world footprint, so four stratified samples
     * are used. A pixel becomes lake if any sub-sample is actual PJ lake water. This is only
     * pixel-coverage preservation; there is no ellipse reconstruction or artificial lake-radius
     * expansion. z4+ samples the pixel centre directly, so local shoreline shape is exactly the
     * current PJ algorithm at the map's raster resolution.</p>
     */
    private boolean renderRuntimeLakes(BufferedImage image, PJMTileKey key,
                                       long globalPixelX, long globalPixelY,
                                       double worldPerPixelX, double worldPerPixelZ) {
        boolean rendered = false;
        int sub = key.zoom() <= 3 ? 2 : 1;
        double inv = 1.0D / sub;

        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                boolean water = false;

                for (int oy = 0; oy < sub && !water; oy++) {
                    for (int ox = 0; ox < sub; ox++) {
                        double sx = globalPixelX + px + (ox + 0.5D) * inv;
                        double sy = globalPixelY + py + (oy + 0.5D) * inv;
                        int worldX = (int)Math.floor(
                                header.minWorldX() + sx * worldPerPixelX);
                        int worldZ = (int)Math.floor(
                                header.minWorldZ() + sy * worldPerPixelZ);

                        if (runtime.lakeWater(worldX, worldZ)) {
                            water = true;
                            break;
                        }
                    }
                }

                if (water) {
                    image.setRGB(px, py, LAKE_ARGB);
                    rendered = true;
                }
            }
        }

        return rendered;
    }

    private void renderVectorRivers(BufferedImage image, boolean[] landMask,
                                    PJMTileKey key,
                                    long globalPixelX, long globalPixelY,
                                    double worldPerPixelX, double worldPerPixelZ) {
        if (!runtime.vectorRiversAvailable()) {
            // Compatibility fallback for a future PJ whose private RiverData implementation changes.
            // It is intentionally limited to local zooms so an incompatible PJ does not bring back
            // the old nationwide performance problem.
            if (key.zoom() >= 8) {
                renderExactFallbackRivers(image, landMask, globalPixelX, globalPixelY,
                        worldPerPixelX, worldPerPixelZ);
            }
            return;
        }

        double tileMinX = header.minWorldX() + globalPixelX * worldPerPixelX;
        double tileMinZ = header.minWorldZ() + globalPixelY * worldPerPixelZ;
        double tileMaxX = tileMinX + TILE_SIZE * worldPerPixelX;
        double tileMaxZ = tileMinZ + TILE_SIZE * worldPerPixelZ;
        double margin = Math.max(worldPerPixelX, worldPerPixelZ) * 2.0D;

        BufferedImage riverLayer =
                new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = riverLayer.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(new java.awt.Color(RIVER_ARGB, true));

            double pixelsPerBlock = 0.5D
                    * (1.0D / worldPerPixelX + 1.0D / worldPerPixelZ);

            for (PJMProjectJapanRuntime.RiverVectorSegment segment :
                    runtime.riverSegments(tileMinX - margin, tileMinZ - margin,
                            tileMaxX + margin, tileMaxZ + margin)) {
                float strokePixels = (float)Math.max(
                        1.0D, segment.widthBlocks() * pixelsPerBlock);

                // Real PJ water width only. No bank/corridor width and no arbitrary low-zoom
                // multiplier. The sole cartographic concession is a 1-pixel visibility floor.
                g.setStroke(new BasicStroke(strokePixels,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                double x1 = (segment.x1() - tileMinX) / worldPerPixelX;
                double y1 = (segment.z1() - tileMinZ) / worldPerPixelZ;
                double x2 = (segment.x2() - tileMinX) / worldPerPixelX;
                double y2 = (segment.z2() - tileMinZ) / worldPerPixelZ;
                g.draw(new Line2D.Double(x1, y1, x2, y2));
            }
        } finally {
            g.dispose();
        }

        // Clip river colour to land / immediate coastline. PJ RiverData intentionally extends
        // sea-bound routes offshore for terrain carving, but that internal extension should not
        // appear as a separate bright river shape in the map ocean.
        for (int y = 0; y < TILE_SIZE; y++) {
            for (int x = 0; x < TILE_SIZE; x++) {
                int river = riverLayer.getRGB(x, y);
                if ((river >>> 24) == 0) continue;

                int idx = y * TILE_SIZE + x;
                if (landMask[idx] || adjacentLand(landMask, x, y)) {
                    image.setRGB(x, y, RIVER_ARGB);
                }
            }
        }
    }

    private void renderExactFallbackRivers(BufferedImage image, boolean[] landMask,
                                           long globalPixelX, long globalPixelY,
                                           double worldPerPixelX, double worldPerPixelZ) {
        for (int py = 0; py < TILE_SIZE; py++) {
            int worldZ = (int)Math.floor(header.minWorldZ()
                    + (globalPixelY + py + 0.5D) * worldPerPixelZ);
            for (int px = 0; px < TILE_SIZE; px++) {
                if (!landMask[py * TILE_SIZE + px]) continue;
                int worldX = (int)Math.floor(header.minWorldX()
                        + (globalPixelX + px + 0.5D) * worldPerPixelX);
                if (runtime.riverWater(worldX, worldZ)) {
                    image.setRGB(px, py, RIVER_ARGB);
                }
            }
        }
    }

    private static boolean adjacentLand(boolean[] landMask, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            int yy = y + dy;
            if (yy < 0 || yy >= TILE_SIZE) continue;
            for (int dx = -1; dx <= 1; dx++) {
                int xx = x + dx;
                if (xx < 0 || xx >= TILE_SIZE) continue;
                if (landMask[yy * TILE_SIZE + xx]) return true;
            }
        }
        return false;
    }

    private static int terrainColor(int elevationMetres) {
        if (elevationMetres < 20) return 0xFFC4D3AD;
        if (elevationMetres < 100) return 0xFFBECDA5;
        if (elevationMetres < 250) return 0xFFB5C49A;
        if (elevationMetres < 500) return 0xFFAAB98C;
        if (elevationMetres < 900) return 0xFFA0AD80;
        if (elevationMetres < 1400) return 0xFF9B9F79;
        if (elevationMetres < 2200) return 0xFF9B9275;
        if (elevationMetres < 3000) return 0xFFA29A88;
        return 0xFFBAB7AE;
    }

    private static int blend(int a, int b, double t) {
        t = clamp01(t);
        int ar = (a >>> 16) & 255;
        int ag = (a >>> 8) & 255;
        int ab = a & 255;
        int br = (b >>> 16) & 255;
        int bg = (b >>> 8) & 255;
        int bb = b & 255;

        int r = (int)Math.round(ar + (br - ar) * t);
        int g = (int)Math.round(ag + (bg - ag) * t);
        int bl = (int)Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private Path tilePath(PJMTileKey key, String extension) {
        return cacheDir.resolve("z" + key.zoom())
                .resolve("x" + key.x())
                .resolve("y" + key.y() + extension);
    }

    private static void cleanupOldAutomaticCaches(Path root, Path current) {
        if (!Files.isDirectory(root)) return;
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory)
                    .filter(path -> !path.equals(current))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(PJMProjectJapanMapSource::deleteRecursivelyQuietly);
        } catch (IOException ignored) {
        }
    }

    private static void deleteRecursivelyQuietly(Path root) {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record VisualAggregate(double landFraction, int elevationMetres, double coverage) {
    }
}
