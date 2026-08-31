package com.sora.pjm.map;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline real-world geography catalogue bundled inside PJM.
 *
 * <p>No HTTP, no config cache and no PJHUD range table is used here. Static geography ships in
 * {@code assets/pjm/geography/*.tsv}; rivers and lakes remain dynamic and are queried from the
 * currently loaded ProjectJapan runtime by {@link PJMGeographyService}.</p>
 */
final class PJMExternalGeodata {
    private static final String CITY_RESOURCE = "/assets/pjm/geography/cities.tsv";
    private static final String MARINE_RESOURCE = "/assets/pjm/geography/marine.tsv";
    private static final String MOUNTAIN_RESOURCE = "/assets/pjm/geography/mountains.tsv";
    private static final String ISLAND_RESOURCE = "/assets/pjm/geography/islands.tsv";

    private static final int MOUNTAIN_DIRECTIONS = 16;
    private static final int MOUNTAIN_STEP = 64;
    private static final int MOUNTAIN_MAX_RADIUS = 4096;
    private static final double MOUNTAIN_OUTSIDE_TOLERANCE = 30.0D;

    private static final int ISLAND_DIRECTIONS = 16;
    private static final int ISLAND_STEP = 64;
    private static final int ISLAND_MAX_RADIUS = 16000;

    private final PJMProjectJapanRuntime runtime;

    private final List<CityFeature> cities = new ArrayList<>();
    private final List<MarineFeature> marines = new ArrayList<>();
    private final List<MountainFeature> mountains = new ArrayList<>();
    private final List<IslandFeature> islands = new ArrayList<>();

    private final Map<String, MountainFoot> mountainFootCache = new HashMap<>();
    private final Map<String, IslandFoot> islandFootCache = new HashMap<>();

    PJMExternalGeodata(PJMProjectJapanRuntime runtime) {
        this.runtime = runtime;
        loadCities();
        loadMarine();
        loadMountains();
        loadIslands();
    }

    int cityCount() {
        return cities.size();
    }

    int marineCount() {
        return marines.size();
    }

    int mountainCount() {
        return mountains.size();
    }

    int islandCount() {
        return islands.size();
    }

    /**
     * Returns a city only while inside its spacing-derived local footprint. If none matches, the
     * caller falls back to the 47-prefecture layer.
     */
    String cityAt(int worldX, int worldZ) {
        CityFeature best = null;
        double bestNormalized = Double.POSITIVE_INFINITY;

        for (CityFeature city : cities) {
            double d = Math.hypot(worldX - city.x, worldZ - city.z);
            if (d > city.radius) continue;
            double normalized = d / Math.max(1.0D, city.radius);
            if (normalized < bestNormalized) {
                best = city;
                bestNormalized = normalized;
            }
        }
        if (best == null) return "";

        // The 23 special wards are administrative subdivisions of Tokyo Metropolis. For the HUD
        // they are intentionally collapsed to a single "东京都" label instead of showing
        // 千代田区 / 新宿区 / ... as though they were separate top-level locations.
        if ("東京都".equals(best.prefecture) && best.name.endsWith("区")) {
            return displayName(best.prefecture);
        }
        return displayName(best.name);
    }

    /**
     * Sea-area support is derived from same-specificity neighbour spacing rather than PJHUD
     * polygons/radii. Specific features override broad ones.
     */
    String marineAt(int worldX, int worldZ) {
        MarineFeature best = null;
        int bestPriority = Integer.MIN_VALUE;
        double bestNormalized = Double.POSITIVE_INFINITY;

        for (MarineFeature marine : marines) {
            double d = Math.hypot(worldX - marine.x, worldZ - marine.z);
            if (d > marine.supportRadius) continue;
            double normalized = d / Math.max(1.0D, marine.supportRadius);

            if (marine.priority > bestPriority
                    || (marine.priority == bestPriority && normalized < bestNormalized)) {
                best = marine;
                bestPriority = marine.priority;
                bestNormalized = normalized;
            }
        }
        return best == null ? "" : displayName(best.name);
    }

    /**
     * Uses a static peak point but derives the mountain foot from the current PJ TerrainData.
     * Display continues no more than 30 blocks outside that foot.
     */
    String mountainAt(int worldX, int worldZ) {
        if (mountains.isEmpty()) return "";

        List<FeatureDistance<MountainFeature>> candidates = new ArrayList<>();
        for (MountainFeature mountain : mountains) {
            double d = Math.hypot(worldX - mountain.x, worldZ - mountain.z);
            if (d <= MOUNTAIN_MAX_RADIUS + MOUNTAIN_OUTSIDE_TOLERANCE + 512.0D) {
                candidates.add(new FeatureDistance<>(mountain, d));
            }
        }
        candidates.sort(Comparator.comparingDouble(FeatureDistance::distance));

        MountainFeature best = null;
        double bestOutside = Double.POSITIVE_INFINITY;

        for (int i = 0; i < Math.min(6, candidates.size()); i++) {
            FeatureDistance<MountainFeature> candidate = candidates.get(i);
            MountainFeature mountain = candidate.feature;
            MountainFoot foot = mountainFootCache.computeIfAbsent(
                    mountain.key(), ignored -> deriveMountainFoot(mountain));

            double angle = Math.atan2(worldZ - mountain.z, worldX - mountain.x);
            double radius = foot.radiusAt(angle);
            double outside = Math.max(0.0D, candidate.distance - radius);

            if (outside <= MOUNTAIN_OUTSIDE_TOLERANCE && outside < bestOutside) {
                best = mountain;
                bestOutside = outside;
            }
        }
        return best == null ? "" : displayName(best.name);
    }

    /** Famous non-four-main-island footprint is derived from current PJ land/ocean. */
    String famousIslandAt(int worldX, int worldZ) {
        if (islands.isEmpty() || !runtime.terrain(worldX, worldZ).land()) return "";

        List<FeatureDistance<IslandFeature>> candidates = new ArrayList<>();
        for (IslandFeature island : islands) {
            double d = Math.hypot(worldX - island.x, worldZ - island.z);
            if (d <= ISLAND_MAX_RADIUS) {
                candidates.add(new FeatureDistance<>(island, d));
            }
        }
        candidates.sort(Comparator.comparingDouble(FeatureDistance::distance));

        for (int i = 0; i < Math.min(5, candidates.size()); i++) {
            FeatureDistance<IslandFeature> candidate = candidates.get(i);
            IslandFeature island = candidate.feature;
            IslandFoot foot = islandFootCache.computeIfAbsent(
                    island.key(), ignored -> deriveIslandFoot(island));
            double angle = Math.atan2(worldZ - island.z, worldX - island.x);
            if (candidate.distance <= foot.radiusAt(angle) + 64.0D) {
                return displayName(island.name);
            }
        }
        return "";
    }

    private void loadCities() {
        readTsv(CITY_RESOURCE, fields -> {
            if (fields.length < 4) return;
            String prefecture = fields[0];
            String name = fields[1];
            Double lat = parseDouble(fields[2]);
            Double lon = parseDouble(fields[3]);
            if (lat == null || lon == null) return;

            cities.add(new CityFeature(
                    prefecture, name,
                    runtime.worldXFromLongitude(lon),
                    runtime.worldZFromLatitude(lat),
                    1000.0D));
        });

        // Radius is derived from spacing to the nearest other city. This is intentionally not
        // copied from PJHUD and keeps dense metro areas tighter than sparse rural city regions.
        for (int i = 0; i < cities.size(); i++) {
            CityFeature city = cities.get(i);
            double nearest = Double.POSITIVE_INFINITY;
            for (int j = 0; j < cities.size(); j++) {
                if (i == j) continue;
                CityFeature other = cities.get(j);
                double d = Math.hypot(city.x - other.x, city.z - other.z);
                if (d < nearest) nearest = d;
            }
            double radius = clamp(Double.isFinite(nearest) ? nearest * 0.47D : 1000.0D,
                    350.0D, 5200.0D);
            cities.set(i, city.withRadius(radius));
        }
    }

    private void loadMarine() {
        List<MarineFeature> raw = new ArrayList<>();
        readTsv(MARINE_RESOURCE, fields -> {
            if (fields.length < 3) return;
            String name = fields[0];
            Double lat = parseDouble(fields[1]);
            Double lon = parseDouble(fields[2]);
            if (lat == null || lon == null) return;
            raw.add(new MarineFeature(
                    name,
                    runtime.worldXFromLongitude(lon),
                    runtime.worldZFromLatitude(lat),
                    marinePriority(name),
                    1.0D));
        });

        for (int i = 0; i < raw.size(); i++) {
            MarineFeature feature = raw.get(i);
            double nearest = Double.POSITIVE_INFINITY;
            for (int j = 0; j < raw.size(); j++) {
                if (i == j) continue;
                MarineFeature other = raw.get(j);
                if (other.priority != feature.priority) continue;
                double d = Math.hypot(feature.x - other.x, feature.z - other.z);
                if (d < nearest) nearest = d;
            }
            marines.add(feature.withSupportRadius(
                    marineSupportRadius(feature.name, feature.priority, nearest)));
        }
    }

    private void loadMountains() {
        readTsv(MOUNTAIN_RESOURCE, fields -> {
            if (fields.length < 3) return;
            Double lat = parseDouble(fields[1]);
            Double lon = parseDouble(fields[2]);
            if (lat == null || lon == null) return;
            mountains.add(new MountainFeature(
                    fields[0],
                    runtime.worldXFromLongitude(lon),
                    runtime.worldZFromLatitude(lat)));
        });
    }

    private void loadIslands() {
        readTsv(ISLAND_RESOURCE, fields -> {
            if (fields.length < 3) return;
            Double lat = parseDouble(fields[1]);
            Double lon = parseDouble(fields[2]);
            if (lat == null || lon == null) return;
            islands.add(new IslandFeature(
                    fields[0],
                    runtime.worldXFromLongitude(lon),
                    runtime.worldZFromLatitude(lat)));
        });
    }

    private MountainFoot deriveMountainFoot(MountainFeature mountain) {
        int peakX = mountain.x;
        int peakZ = mountain.z;
        int peakElevation = runtime.terrain(peakX, peakZ).elevationMetres();

        // GSI/gazetteer peak coordinates can land slightly off the local raster summit after PJ
        // down-scaling. Probe a small neighbourhood first.
        for (int dz = -128; dz <= 128; dz += 64) {
            for (int dx = -128; dx <= 128; dx += 64) {
                peakElevation = Math.max(peakElevation,
                        runtime.terrain(peakX + dx, peakZ + dz).elevationMetres());
            }
        }

        double[] radii = new double[MOUNTAIN_DIRECTIONS];
        double requiredDrop = Math.max(24.0D, peakElevation * 0.16D);

        for (int direction = 0; direction < MOUNTAIN_DIRECTIONS; direction++) {
            double angle = Math.PI * 2.0D * direction / MOUNTAIN_DIRECTIONS;
            int previous = peakElevation;
            int flatSteps = 0;
            double foot = MOUNTAIN_MAX_RADIUS;

            for (int d = MOUNTAIN_STEP; d <= MOUNTAIN_MAX_RADIUS; d += MOUNTAIN_STEP) {
                int sx = peakX + (int)Math.round(Math.cos(angle) * d);
                int sz = peakZ + (int)Math.round(Math.sin(angle) * d);
                PJMProjectJapanRuntime.TerrainPixel sample = runtime.terrain(sx, sz);
                int elevation = sample.land() ? sample.elevationMetres() : 0;

                double drop = peakElevation - elevation;
                int slope = Math.abs(previous - elevation);
                if (drop >= requiredDrop && slope <= 3) flatSteps++;
                else flatSteps = 0;

                if (!sample.land() || elevation <= 8
                        || (drop >= requiredDrop && flatSteps >= 2)) {
                    foot = Math.max(MOUNTAIN_STEP, d - MOUNTAIN_STEP);
                    break;
                }
                previous = elevation;
            }
            radii[direction] = foot;
        }
        return new MountainFoot(radii);
    }

    private IslandFoot deriveIslandFoot(IslandFeature island) {
        double[] radii = new double[ISLAND_DIRECTIONS];
        for (int direction = 0; direction < ISLAND_DIRECTIONS; direction++) {
            double angle = Math.PI * 2.0D * direction / ISLAND_DIRECTIONS;
            double edge = ISLAND_MAX_RADIUS;
            for (int d = ISLAND_STEP; d <= ISLAND_MAX_RADIUS; d += ISLAND_STEP) {
                int sx = island.x + (int)Math.round(Math.cos(angle) * d);
                int sz = island.z + (int)Math.round(Math.sin(angle) * d);
                if (!runtime.terrain(sx, sz).land()) {
                    edge = Math.max(ISLAND_STEP, d - ISLAND_STEP);
                    break;
                }
            }
            radii[direction] = edge;
        }
        return new IslandFoot(radii);
    }

    private static void readTsv(String path, RowConsumer consumer) {
        try (InputStream stream = PJMExternalGeodata.class.getResourceAsStream(path)) {
            if (stream == null) return;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("#")) continue;
                    consumer.accept(line.split("\\t", -1));
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static Double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int marinePriority(String name) {
        if (name.contains("海峡") || name.contains("水道") || name.contains("瀬戸")) return 4;
        if (name.contains("湾") || name.endsWith("浦")) return 3;
        if (name.contains("内海") || name.contains("灘")) return 2;
        if (name.contains("海")) return 1;
        if (name.contains("洋")) return 0;
        return 1;
    }

    /**
     * Marine anchors represent areas of very different physical scale. Narrow passages must not
     * override a bay/sea from thousands of blocks away, while broad seas still need a large
     * support footprint. Distances here describe support around the bundled anchor, not inland
     * visibility (which is separately limited in PJMGeographyService).
     */
    private static double marineSupportRadius(String name, int priority,
                                              double nearestSamePriority) {
        double min;
        double max;
        double spacingFactor;

        if (name.contains("瀬戸")) {
            min = 160.0D; max = 900.0D; spacingFactor = 0.18D;
        } else if (name.contains("海峡")) {
            min = 320.0D; max = 2800.0D; spacingFactor = 0.28D;
        } else if (name.contains("水道")) {
            min = 260.0D; max = 2400.0D; spacingFactor = 0.25D;
        } else if (name.contains("湾") || name.endsWith("浦")) {
            min = 700.0D; max = 5200.0D; spacingFactor = 0.42D;
        } else if (name.contains("内海") || name.contains("灘")) {
            min = 1800.0D; max = 10000.0D; spacingFactor = 0.50D;
        } else if (name.contains("洋")) {
            min = 12000.0D; max = 60000.0D; spacingFactor = 0.60D;
        } else if (name.contains("海")) {
            min = 5000.0D; max = 26000.0D; spacingFactor = 0.55D;
        } else {
            // Keep an intentionally conservative fallback tied to the existing priority model.
            switch (priority) {
                case 4 -> { min = 300.0D; max = 2600.0D; spacingFactor = 0.27D; }
                case 3 -> { min = 700.0D; max = 5200.0D; spacingFactor = 0.42D; }
                case 2 -> { min = 1800.0D; max = 10000.0D; spacingFactor = 0.50D; }
                case 1 -> { min = 5000.0D; max = 26000.0D; spacingFactor = 0.55D; }
                default -> { min = 12000.0D; max = 60000.0D; spacingFactor = 0.60D; }
            }
        }

        double derived = Double.isFinite(nearestSamePriority)
                ? nearestSamePriority * spacingFactor : min;
        return clamp(derived, min, max);
    }

    /** Light Japanese -> simplified-Chinese presentation normalization; names remain real names. */
    private static String displayName(String name) {
        return name
                .replace("東京", "东京")
                .replace("横浜", "横滨")
                .replace("広島", "广岛")
                .replace("長崎", "长崎")
                .replace("長野", "长野")
                .replace("鹿児島", "鹿儿岛")
                .replace("沖縄", "冲绳")
                .replace("対馬", "对马")
                .replace("津軽", "津轻")
                .replace("関門", "关门")
                .replace("豊後", "丰后")
                .replace("駿河", "骏河")
                .replace("瀬戸", "濑户")
                .replace("島", "岛")
                .replace("広", "广")
                .replace("長", "长")
                .replace("徳", "德")
                .replace("浜", "滨")
                .replace("岡", "冈")
                .replace("沢", "泽")
                .replace("戸", "户");
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double interpolatedRadius(double[] radii, double angle) {
        double normalized = angle;
        while (normalized < 0.0D) normalized += Math.PI * 2.0D;
        while (normalized >= Math.PI * 2.0D) normalized -= Math.PI * 2.0D;

        double sector = normalized / (Math.PI * 2.0D) * radii.length;
        int i0 = (int)Math.floor(sector) % radii.length;
        int i1 = (i0 + 1) % radii.length;
        double t = sector - Math.floor(sector);
        return radii[i0] * (1.0D - t) + radii[i1] * t;
    }

    @FunctionalInterface
    private interface RowConsumer {
        void accept(String[] fields);
    }

    private record CityFeature(String prefecture, String name, int x, int z, double radius) {
        CityFeature withRadius(double value) {
            return new CityFeature(prefecture, name, x, z, value);
        }
    }

    private record MarineFeature(String name, int x, int z, int priority, double supportRadius) {
        MarineFeature withSupportRadius(double radius) {
            return new MarineFeature(name, x, z, priority, radius);
        }
    }

    private record MountainFeature(String name, int x, int z) {
        String key() { return name + ":" + x + ":" + z; }
    }

    private record IslandFeature(String name, int x, int z) {
        String key() { return name + ":" + x + ":" + z; }
    }

    private record FeatureDistance<T>(T feature, double distance) {}

    private record MountainFoot(double[] radii) {
        double radiusAt(double angle) { return interpolatedRadius(radii, angle); }
    }

    private record IslandFoot(double[] radii) {
        double radiusAt(double angle) { return interpolatedRadius(radii, angle); }
    }
}
