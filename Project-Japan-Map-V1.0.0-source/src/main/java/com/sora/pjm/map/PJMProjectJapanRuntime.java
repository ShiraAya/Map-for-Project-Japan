package com.sora.pjm.map;

import net.minecraftforge.fml.ModList;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter for the ProjectJapan classes that are actually loaded by Forge.
 *
 * <p>0.9 has two paths:
 * <ul>
 *   <li>Terrain pixels are read directly from the current PJ land/height resources. This is much
 *       faster than invoking TerrainData.sampleWorld() tens of thousands of times per map tile.</li>
 *   <li>River display geometry is extracted from RiverData.COURSES after PJ has already performed
 *       its spline smoothing, valley snapping, densification and offshore extension. The map
 *       therefore follows the currently loaded PJ's final centre-lines instead of copying a river
 *       table into PJM.</li>
 * </ul></p>
 */
final class PJMProjectJapanRuntime {
    static final String PJ_MOD_ID = "projectjapan";
    private static final int RIVER_INDEX_GRID = 8192;

    private final Class<?> terrainClass;
    private final Class<?> riverClass;
    private final Class<?> lakeClass;

    private final MethodHandle terrainSampleWorld;
    private final MethodHandle terrainLand;
    private final MethodHandle terrainElevation;
    private final MethodHandle terrainCoverage;
    private final MethodHandle riverWater;
    private final MethodHandle lakeWater;

    private final MethodHandle riverSampleWorld;
    private final MethodHandle riverSampleCorridor;
    private final MethodHandle riverSampleWater;
    private final MethodHandle riverSampleName;
    private final MethodHandle lakeSampleWorld;
    private final MethodHandle lakeSampleCorridor;
    private final MethodHandle lakeSampleWater;
    private final MethodHandle lakeSampleName;
    private final MethodHandle worldXFromLongitude;
    private final MethodHandle worldZFromLatitude;

    private final BufferedImage landImage;
    private final BufferedImage heightImage;
    private final Raster landRaster;
    private final Raster heightRaster;

    private final List<RiverVectorSegment> riverSegments;
    private final Map<Long, List<RiverVectorSegment>> riverSegmentGrid;
    private final boolean vectorRiversAvailable;


    private final double minLongitude;
    private final double maxLongitude;
    private final double minLatitude;
    private final double maxLatitude;
    private final String pjVersion;
    private final String fingerprint;

    static boolean isAvailable() {
        return ModList.get().isLoaded(PJ_MOD_ID);
    }

    PJMProjectJapanRuntime() throws ReflectiveOperationException, IOException {
        terrainClass = Class.forName("com.sora.projectjapan.worldgen.TerrainData");
        riverClass = Class.forName("com.sora.projectjapan.worldgen.RiverData");
        lakeClass = Class.forName("com.sora.projectjapan.worldgen.LakeData");

        MethodHandles.Lookup lookup = MethodHandles.publicLookup();

        Method terrainMethod = terrainClass.getMethod("sampleWorld", int.class, int.class);
        Class<?> terrainSampleClass = terrainMethod.getReturnType();
        terrainSampleWorld = lookup.unreflect(terrainMethod);
        terrainLand = lookup.findVirtual(
                terrainSampleClass, "land", MethodType.methodType(boolean.class));
        terrainElevation = lookup.findVirtual(
                terrainSampleClass, "elevationMetres", MethodType.methodType(int.class));
        terrainCoverage = lookup.findVirtual(
                terrainSampleClass, "landCoverage", MethodType.methodType(double.class));

        riverWater = lookup.unreflect(
                riverClass.getMethod("isRiverWaterWorld", int.class, int.class));
        lakeWater = lookup.unreflect(
                lakeClass.getMethod("isLakeWaterWorld", int.class, int.class));

        Method riverSampleMethod = riverClass.getMethod("sampleWorld", int.class, int.class);
        Class<?> riverSampleClass = riverSampleMethod.getReturnType();
        riverSampleWorld = lookup.unreflect(riverSampleMethod);
        riverSampleCorridor = lookup.findVirtual(
                riverSampleClass, "corridor", MethodType.methodType(boolean.class));
        riverSampleWater = lookup.findVirtual(
                riverSampleClass, "water", MethodType.methodType(boolean.class));
        riverSampleName = lookup.findVirtual(
                riverSampleClass, "name", MethodType.methodType(String.class));

        Method lakeSampleMethod = lakeClass.getMethod("sampleWorld", int.class, int.class);
        Class<?> lakeSampleClass = lakeSampleMethod.getReturnType();
        lakeSampleWorld = lookup.unreflect(lakeSampleMethod);
        lakeSampleCorridor = lookup.findVirtual(
                lakeSampleClass, "corridor", MethodType.methodType(boolean.class));
        lakeSampleWater = lookup.findVirtual(
                lakeSampleClass, "water", MethodType.methodType(boolean.class));
        lakeSampleName = lookup.findVirtual(
                lakeSampleClass, "name", MethodType.methodType(String.class));

        worldXFromLongitude = lookup.unreflect(
                terrainClass.getMethod("worldXFromLongitude", double.class));
        worldZFromLatitude = lookup.unreflect(
                terrainClass.getMethod("worldZFromLatitude", double.class));

        minLongitude = readGeoBound("MIN_LON", 122.0D);
        maxLongitude = readGeoBound("MAX_LON", 154.0D);
        minLatitude = readGeoBound("MIN_LAT", 20.0D);
        maxLatitude = readGeoBound("MAX_LAT", 46.0D);

        landImage = loadTerrainImage("japan_land_hd.png");
        heightImage = loadTerrainImage("japan_height.png");
        landRaster = landImage.getRaster();
        heightRaster = heightImage.getRaster();

        List<RiverVectorSegment> extracted;
        try {
            extracted = extractRiverVectors();
        } catch (ReflectiveOperationException | RuntimeException incompatibleInternals) {
            extracted = List.of();
        }
        riverSegments = List.copyOf(extracted);
        riverSegmentGrid = buildRiverIndex(riverSegments);
        vectorRiversAvailable = !riverSegments.isEmpty();


        pjVersion = ModList.get().getModContainerById(PJ_MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
        fingerprint = calculateFingerprint();
    }

    String version() {
        return pjVersion;
    }

    String fingerprint() {
        return fingerprint;
    }

    int minWorldX() {
        return worldXFromLongitude(minLongitude);
    }

    int maxWorldX() {
        return worldXFromLongitude(maxLongitude);
    }

    int minWorldZ() {
        return worldZFromLatitude(maxLatitude);
    }

    int maxWorldZ() {
        return worldZFromLatitude(minLatitude);
    }

    int landImageWidth() {
        return landImage.getWidth();
    }

    int landImageHeight() {
        return landImage.getHeight();
    }

    /**
     * Fast visual sample of the exact terrain resources bundled in the currently loaded PJ.
     *
     * <p>u/v are normalized over PJ's national coordinate envelope. Land uses the same bilinear
     * grayscale threshold as TerrainData. Height is bilinear too; this map sample deliberately
     * does not call the expensive per-column regional correction functions because those corrections
     * do not add source-map detail and are intended for generated terrain rather than cartography.</p>
     */
    VisualTerrain visualTerrain(double u, double v) {
        u = clamp01(u);
        v = clamp01(v);

        double coverage = sampleBilinear(landRaster, landImage.getWidth(), landImage.getHeight(),
                u, v) / 255.0D;
        boolean land = coverage >= 0.5D;
        int elevation = land
                ? Math.max(0, (int)Math.round(sampleBilinear(
                        heightRaster, heightImage.getWidth(), heightImage.getHeight(), u, v)))
                : 0;
        return new VisualTerrain(land, elevation, coverage);
    }

    /**
     * Exact TerrainData runtime call retained as a compatibility fallback/debug hook.
     */
    TerrainPixel terrain(int worldX, int worldZ) {
        try {
            Object sample = terrainSampleWorld.invoke(worldX, worldZ);
            return new TerrainPixel(
                    (boolean) terrainLand.invoke(sample),
                    (int) terrainElevation.invoke(sample),
                    (double) terrainCoverage.invoke(sample));
        } catch (Throwable throwable) {
            throw new RuntimeException("ProjectJapan TerrainData sampling failed", throwable);
        }
    }

    boolean riverWater(int worldX, int worldZ) {
        try {
            return (boolean) riverWater.invoke(worldX, worldZ);
        } catch (Throwable throwable) {
            throw new RuntimeException("ProjectJapan RiverData sampling failed", throwable);
        }
    }

    NamedHydroSample riverSample(int worldX, int worldZ) {
        try {
            Object sample = riverSampleWorld.invoke(worldX, worldZ);
            return new NamedHydroSample(
                    (boolean) riverSampleCorridor.invoke(sample),
                    (boolean) riverSampleWater.invoke(sample),
                    (String) riverSampleName.invoke(sample));
        } catch (Throwable throwable) {
            throw new RuntimeException("ProjectJapan RiverData named sampling failed", throwable);
        }
    }

    /**
     * The only lake-shape API PJM uses.
     *
     * <p>This delegates to the LakeData class loaded by Forge right now. PJM does not inspect
     * LakeData.LAKES, lobes, polygons, masks or any other private representation, so ProjectJapan
     * is free to completely replace its shoreline implementation without a PJM update.</p>
     */
    boolean lakeWater(int worldX, int worldZ) {
        try {
            return (boolean) lakeWater.invoke(worldX, worldZ);
        } catch (Throwable throwable) {
            throw new RuntimeException("ProjectJapan LakeData sampling failed", throwable);
        }
    }

    NamedHydroSample lakeSample(int worldX, int worldZ) {
        try {
            Object sample = lakeSampleWorld.invoke(worldX, worldZ);
            return new NamedHydroSample(
                    (boolean) lakeSampleCorridor.invoke(sample),
                    (boolean) lakeSampleWater.invoke(sample),
                    (String) lakeSampleName.invoke(sample));
        } catch (Throwable throwable) {
            throw new RuntimeException("ProjectJapan LakeData named sampling failed", throwable);
        }
    }

    boolean vectorRiversAvailable() {
        return vectorRiversAvailable;
    }

    List<RiverVectorSegment> riverSegments(double minX, double minZ,
                                           double maxX, double maxZ) {
        if (!vectorRiversAvailable) return List.of();

        int cellMinX = floorCell(minX);
        int cellMaxX = floorCell(maxX);
        int cellMinZ = floorCell(minZ);
        int cellMaxZ = floorCell(maxZ);

        Set<RiverVectorSegment> unique = new HashSet<>();
        for (int gz = cellMinZ; gz <= cellMaxZ; gz++) {
            for (int gx = cellMinX; gx <= cellMaxX; gx++) {
                List<RiverVectorSegment> cell = riverSegmentGrid.get(gridKey(gx, gz));
                if (cell == null) continue;
                for (RiverVectorSegment segment : cell) {
                    if (segment.maxX() < minX || segment.minX() > maxX
                            || segment.maxZ() < minZ || segment.minZ() > maxZ) {
                        continue;
                    }
                    unique.add(segment);
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private List<RiverVectorSegment> extractRiverVectors()
            throws ReflectiveOperationException {
        Field coursesField = riverClass.getDeclaredField("COURSES");
        coursesField.setAccessible(true);
        Object coursesObject = coursesField.get(null);
        if (!(coursesObject instanceof List<?> courses)) return List.of();

        List<RiverVectorSegment> result = new ArrayList<>();

        for (Object course : courses) {
            Class<?> courseClass = course.getClass();
            Field segmentsField = accessibleField(courseClass, "segments");
            Field mouthDistanceField = accessibleField(courseClass, "mouthDistance");
            Field reachesSeaField = accessibleField(courseClass, "reachesSea");
            Field sourceWidthField = accessibleField(courseClass, "sourceWidth");
            Field mouthWidthField = accessibleField(courseClass, "mouthWidth");

            @SuppressWarnings("unchecked")
            List<Object> segments = (List<Object>) segmentsField.get(course);
            double mouthDistance = mouthDistanceField.getDouble(course);
            boolean reachesSea = reachesSeaField.getBoolean(course);
            int sourceWidth = sourceWidthField.getInt(course);
            int mouthWidth = mouthWidthField.getInt(course);

            for (Object segment : segments) {
                Class<?> segmentClass = segment.getClass();
                Field startField = accessibleField(segmentClass, "start");
                Field endField = accessibleField(segmentClass, "end");
                Field startDistanceField = accessibleField(segmentClass, "startDistance");
                Field lengthField = accessibleField(segmentClass, "length");

                Object start = startField.get(segment);
                Object end = endField.get(segment);

                double x1 = readDoubleField(start, "x");
                double z1 = readDoubleField(start, "z");
                double x2 = readDoubleField(end, "x");
                double z2 = readDoubleField(end, "z");
                double startDistance = startDistanceField.getDouble(segment);
                double length = lengthField.getDouble(segment);

                double progress = clamp(
                        (startDistance + length * 0.5D) / Math.max(1.0D, mouthDistance),
                        0.0D, 1.0D);
                double width = riverWaterWidth(progress, reachesSea, sourceWidth, mouthWidth);

                double radius = width * 0.5D + 2.0D;
                result.add(new RiverVectorSegment(
                        x1, z1, x2, z2, width,
                        Math.min(x1, x2) - radius,
                        Math.max(x1, x2) + radius,
                        Math.min(z1, z2) - radius,
                        Math.max(z1, z2) + radius));
            }
        }
        return result;
    }

    private static Field accessibleField(Class<?> owner, String name)
            throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static double readDoubleField(Object owner, String name)
            throws ReflectiveOperationException {
        Field field = accessibleField(owner.getClass(), name);
        return field.getDouble(owner);
    }

    private static double riverWaterWidth(double progress, boolean reachesSea,
                                          int sourceWidth, int mouthWidth) {
        double p = smoothstep(0.0D, 1.0D, progress);
        double estuary = reachesSea ? smoothstep(0.84D, 1.0D, progress) : 0.0D;
        double width = sourceWidth + (mouthWidth - sourceWidth) * Math.pow(p, 0.78D);
        return width * (1.0D + estuary * 0.12D);
    }

    private static Map<Long, List<RiverVectorSegment>> buildRiverIndex(
            List<RiverVectorSegment> segments) {
        Map<Long, List<RiverVectorSegment>> grid = new HashMap<>();
        for (RiverVectorSegment segment : segments) {
            int minGX = floorCell(segment.minX());
            int maxGX = floorCell(segment.maxX());
            int minGZ = floorCell(segment.minZ());
            int maxGZ = floorCell(segment.maxZ());

            for (int gz = minGZ; gz <= maxGZ; gz++) {
                for (int gx = minGX; gx <= maxGX; gx++) {
                    grid.computeIfAbsent(gridKey(gx, gz), ignored -> new ArrayList<>())
                            .add(segment);
                }
            }
        }
        return grid;
    }

    private static int floorCell(double world) {
        return (int)Math.floor(world / RIVER_INDEX_GRID);
    }

    private static long gridKey(int gx, int gz) {
        return ((long)gx << 32) ^ (gz & 0xffffffffL);
    }

    private BufferedImage loadTerrainImage(String name) throws IOException {
        String path = "/assets/projectjapan/terrain/" + name;
        try (InputStream in = terrainClass.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing PJ terrain resource: " + path);
            BufferedImage image = ImageIO.read(in);
            if (image == null) throw new IOException("Unreadable PJ terrain resource: " + path);
            return image;
        }
    }

    private double readGeoBound(String fieldName, double fallback) {
        try {
            Field field = terrainClass.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getDouble(null);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return fallback;
        }
    }

    int worldXFromLongitude(double longitude) {
        try {
            return (int) worldXFromLongitude.invoke(longitude);
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    int worldZFromLatitude(double latitude) {
        try {
            return (int) worldZFromLatitude.invoke(latitude);
        } catch (Throwable throwable) {
            throw new RuntimeException(throwable);
        }
    }

    /**
     * Inverse of ProjectJapan's public longitude -> world-X transform, obtained by calibrating
     * against PJ's own published geographic envelope. No copy of PJ's scale constants is required.
     */
    double longitudeFromWorldX(double worldX) {
        double x0 = worldXFromLongitude(minLongitude);
        double x1 = worldXFromLongitude(maxLongitude);
        if (Math.abs(x1 - x0) < 1.0E-9D) return minLongitude;
        double t = (worldX - x0) / (x1 - x0);
        return minLongitude + t * (maxLongitude - minLongitude);
    }

    /**
     * Inverse of ProjectJapan's public latitude -> world-Z transform.
     */
    double latitudeFromWorldZ(double worldZ) {
        double zNorth = worldZFromLatitude(maxLatitude);
        double zSouth = worldZFromLatitude(minLatitude);
        if (Math.abs(zSouth - zNorth) < 1.0E-9D) return maxLatitude;
        double t = (worldZ - zNorth) / (zSouth - zNorth);
        return maxLatitude + t * (minLatitude - maxLatitude);
    }

    private String calculateFingerprint() throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }

        digest.update(pjVersion.getBytes(StandardCharsets.UTF_8));
        updateResourceDigest(digest, terrainClass,
                "/com/sora/projectjapan/worldgen/TerrainData.class");
        updateResourceDigest(digest, riverClass,
                "/com/sora/projectjapan/worldgen/RiverData.class");
        updateResourceDigest(digest, lakeClass,
                "/com/sora/projectjapan/worldgen/LakeData.class");
        updateResourceDigest(digest, terrainClass,
                "/assets/projectjapan/terrain/japan_height.png");
        updateResourceDigest(digest, terrainClass,
                "/assets/projectjapan/terrain/japan_land_hd.png");

        return HexFormat.of().formatHex(digest.digest()).substring(0, 20);
    }

    private static void updateResourceDigest(MessageDigest digest, Class<?> anchor, String path)
            throws IOException {
        try (InputStream in = anchor.getResourceAsStream(path)) {
            if (in == null) {
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private static double sampleBilinear(Raster raster, int width, int height,
                                         double u, double v) {
        double px = clamp01(u) * (width - 1);
        double py = clamp01(v) * (height - 1);
        int x0 = Math.max(0, Math.min(width - 1, (int)Math.floor(px)));
        int y0 = Math.max(0, Math.min(height - 1, (int)Math.floor(py)));
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);
        double tx = px - Math.floor(px);
        double ty = py - Math.floor(py);

        double a = raster.getSampleDouble(x0, y0, 0);
        double b = raster.getSampleDouble(x1, y0, 0);
        double c = raster.getSampleDouble(x0, y1, 0);
        double d = raster.getSampleDouble(x1, y1, 0);
        return a * (1.0D - tx) * (1.0D - ty)
                + b * tx * (1.0D - ty)
                + c * (1.0D - tx) * ty
                + d * tx * ty;
    }

    private static double smoothstep(double edge0, double edge1, double value) {
        double t = clamp((value - edge0) / Math.max(0.000001D, edge1 - edge0), 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    record VisualTerrain(boolean land, int elevationMetres, double coverage) {
    }

    record TerrainPixel(boolean land, int elevationMetres, double coverage) {
    }

    record NamedHydroSample(boolean corridor, boolean water, String name) {
        boolean named() {
            return name != null && !name.isBlank();
        }
    }

    record RiverVectorSegment(double x1, double z1, double x2, double z2,
                              double widthBlocks,
                              double minX, double maxX, double minZ, double maxZ) {
    }

}
