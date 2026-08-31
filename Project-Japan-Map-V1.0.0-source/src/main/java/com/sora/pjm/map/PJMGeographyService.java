package com.sora.pjm.map;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * Real-world geography context shown below the minimap.
 *
 * <p>0.19: all static geography is bundled in the PJM jar. There is no HTTP/cache loading.
 * River/lake geometry still comes from the currently loaded ProjectJapan runtime. One displayed
 * metre equals one Minecraft block for HUD proximity thresholds.</p>
 */
public final class PJMGeographyService {
    public static final PJMGeographyService INSTANCE = new PJMGeographyService();

    private static final int UPDATE_INTERVAL_TICKS = 60;
    private static final int MOVE_REFRESH_BLOCKS = 32;

    // Local HUD labels should describe features the player is actually near, not features
    // hundreds of blocks away. River/lake proximity is therefore a strict 30-block radius.
    private static final int RIVER_SEARCH_RADIUS = 30;
    private static final int RIVER_SEARCH_STEP = 1;
    private static final int LAKE_SEARCH_RADIUS = 30;
    private static final int LAKE_SEARCH_STEP = 1;

    // Marine names may reasonably carry a little farther inland than a river/lake label, but the
    // allowed distance is type-sensitive (strait/channel < bay < sea) in nearbyMarine().
    private static final int COAST_SEARCH_RADIUS = 120;
    private static final int COAST_SEARCH_STEP = 4;

    private final ExecutorService worker;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile boolean pending;
    private PJMExternalGeodata external;

    private PJMProjectJapanRuntime runtime;
    private List<FallbackAnchor> fallbackPrefectures = List.of();

    private int lastX = Integer.MIN_VALUE;
    private int lastZ = Integer.MIN_VALUE;
    private int lastTick = Integer.MIN_VALUE;

    private PJMGeographyService() {
        worker = Executors.newSingleThreadExecutor(daemonFactory("PJM-Geography"));
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void tick(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }

        int x = Mth.floor(minecraft.player.getX());
        int z = Mth.floor(minecraft.player.getZ());
        int tick = minecraft.player.tickCount;

        boolean moved = lastX == Integer.MIN_VALUE
                || Math.abs(x - lastX) >= MOVE_REFRESH_BLOCKS
                || Math.abs(z - lastZ) >= MOVE_REFRESH_BLOCKS;
        boolean elapsed = lastTick == Integer.MIN_VALUE
                || tick - lastTick >= UPDATE_INTERVAL_TICKS;
        if (pending || (!moved && !elapsed)) return;

        pending = true;
        lastX = x;
        lastZ = z;
        lastTick = tick;

        worker.execute(() -> {
            try {
                ensureRuntime();
                snapshot = runtime == null ? Snapshot.EMPTY : compute(x, z);
            } catch (Throwable ignored) {
                // Keep the previous valid snapshot. Individual layers are isolated in compute().
            } finally {
                pending = false;
            }
        });
    }

    public void reset() {
        snapshot = Snapshot.EMPTY;
        runtime = null;
        external = null;
        fallbackPrefectures = List.of();
        pending = false;
        lastX = Integer.MIN_VALUE;
        lastZ = Integer.MIN_VALUE;
        lastTick = Integer.MIN_VALUE;
    }

    /** Initializes PJ runtime + bundled geography on the existing background geography worker. */
    private void ensureRuntime() throws ReflectiveOperationException, IOException {
        if (runtime != null || !PJMProjectJapanRuntime.isAvailable()) return;
        runtime = new PJMProjectJapanRuntime();
        fallbackPrefectures = resolveFallbackPrefectures(runtime);
        external = new PJMExternalGeodata(runtime);
    }

    private Snapshot compute(int x, int z) {
        String fallbackLocation = safeLayer(() -> fallbackPrefectureAt(x, z));

        String location = safeLayer(() -> {
            PJMExternalGeodata data = external;
            if (data == null || data.cityCount() <= 0) return fallbackLocation;
            String resolved = data.cityAt(x, z);
            return resolved == null || resolved.isBlank() ? fallbackLocation : resolved;
        });
        if (location.isBlank()) location = fallbackLocation;

        String marine = safeLayer(() -> nearbyMarine(x, z));
        String mountain = safeLayer(() -> {
            PJMExternalGeodata data = external;
            return data == null || data.mountainCount() <= 0 ? "" : data.mountainAt(x, z);
        });
        String hydro = safeLayer(() -> nearbyHydrology(x, z));
        String island = safeLayer(() -> {
            PJMExternalGeodata data = external;
            return data == null ? "" : data.famousIslandAt(x, z);
        });

        return new Snapshot(location, marine, mountain, hydro, island);
    }

    private static String safeLayer(Supplier<String> supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String fallbackPrefectureAt(int x, int z) {
        FallbackAnchor nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (FallbackAnchor anchor : fallbackPrefectures) {
            double d = Math.hypot(x - anchor.x, z - anchor.z);
            if (d < best) {
                best = d;
                nearest = anchor;
            }
        }
        return nearest == null ? "" : nearest.name;
    }

    private static List<FallbackAnchor> resolveFallbackPrefectures(PJMProjectJapanRuntime runtime) {
        List<FallbackAnchor> result = new ArrayList<>(PREFECTURE_FALLBACKS.size());
        for (FallbackGeo def : PREFECTURE_FALLBACKS) {
            result.add(new FallbackAnchor(
                    def.name,
                    runtime.worldXFromLongitude(def.longitude),
                    runtime.worldZFromLatitude(def.latitude)));
        }
        return List.copyOf(result);
    }

    private String nearbyMarine(int x, int z) {
        PJMExternalGeodata data = external;
        if (data == null || data.marineCount() <= 0) return "";

        if (runtime.lakeSample(x, z).water() || runtime.riverSample(x, z).water()) {
            return "";
        }

        PJMProjectJapanRuntime.TerrainPixel terrain = runtime.terrain(x, z);
        if (!terrain.land()) {
            return data.marineAt(x, z);
        }

        OceanPoint coast = nearestOceanPoint(x, z);
        if (coast == null) return "";

        String name = data.marineAt(coast.x, coast.z);
        if (name.isBlank()) return "";
        return coast.distance <= marineInlandDisplayDistance(name) ? name : "";
    }

    private OceanPoint nearestOceanPoint(int x, int z) {
        int maxRing = COAST_SEARCH_RADIUS / COAST_SEARCH_STEP;

        for (int ring = 1; ring <= maxRing; ring++) {
            int r = ring * COAST_SEARCH_STEP;
            OceanPoint best = null;
            double bestDistance = Double.POSITIVE_INFINITY;

            for (int i = -ring; i <= ring; i++) {
                int d = i * COAST_SEARCH_STEP;
                int[][] offsets = {
                        {d, -r}, {d, r}, {-r, d}, {r, d}
                };

                for (int[] offset : offsets) {
                    int sx = x + offset[0];
                    int sz = z + offset[1];

                    if (runtime.lakeSample(sx, sz).water()
                            || runtime.riverSample(sx, sz).water()) {
                        continue;
                    }
                    if (runtime.terrain(sx, sz).land()) continue;

                    double distance = Math.hypot(offset[0], offset[1]);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new OceanPoint(sx, sz, distance);
                    }
                }
            }

            if (best != null) return best;
        }
        return null;
    }

    /**
     * River and lake are independent HUD categories: each category contributes at most its single
     * nearest named water body, and both categories may be visible at the same time.
     */
    private String nearbyHydrology(int x, int z) {
        String lake = findNearbyHydro(
                x, z, true, LAKE_SEARCH_RADIUS, LAKE_SEARCH_STEP);
        String river = findNearbyHydro(
                x, z, false, RIVER_SEARCH_RADIUS, RIVER_SEARCH_STEP);

        String lakeDisplay = lake.isBlank() ? "" : displayLakeName(lake);
        String riverDisplay = river.isBlank() ? "" : displayRiverName(river);
        if (lakeDisplay.isBlank()) return riverDisplay;
        if (riverDisplay.isBlank()) return lakeDisplay;
        return lakeDisplay + " · " + riverDisplay;
    }

    /** Returns the genuinely nearest named water sample inside the requested radius. */
    private String findNearbyHydro(int x, int z, boolean lake,
                                   int radius, int step) {
        PJMProjectJapanRuntime.NamedHydroSample direct = lake
                ? runtime.lakeSample(x, z)
                : runtime.riverSample(x, z);
        if (direct.named() && direct.water()) return direct.name();

        String bestName = "";
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        int safeStep = Math.max(1, step);
        int radiusSq = radius * radius;

        for (int dz = -radius; dz <= radius; dz += safeStep) {
            for (int dx = -radius; dx <= radius; dx += safeStep) {
                if (dx == 0 && dz == 0) continue;
                int distanceSq = dx * dx + dz * dz;
                if (distanceSq > radiusSq || distanceSq >= bestDistanceSq) continue;

                PJMProjectJapanRuntime.NamedHydroSample sample = lake
                        ? runtime.lakeSample(x + dx, z + dz)
                        : runtime.riverSample(x + dx, z + dz);
                if (!sample.named() || !sample.water()) continue;

                bestDistanceSq = distanceSq;
                bestName = sample.name();
            }
        }
        return bestName;
    }

    /**
     * Inland visibility for marine labels is deliberately smaller for narrow named passages.
     * The marine catalogue itself still decides which water area owns the sampled ocean point.
     */
    private static double marineInlandDisplayDistance(String name) {
        if (name.contains("海峡") || name.contains("水道") || name.contains("瀬戸")
                || name.contains("濑户")) return 60.0D;
        if (name.contains("湾") || name.endsWith("浦")) return 80.0D;
        if (name.contains("内海") || name.contains("灘")) return 100.0D;
        if (name.contains("海") || name.contains("洋")) return 120.0D;
        return 80.0D;
    }

    private static String displayLakeName(String raw) {
        String mapped = LAKE_NAMES.get(raw);
        if (mapped != null) return mapped;

        // Newer ProjectJapan hydrology entries may already expose their complete display name.
        // Do not blindly append another type suffix (e.g. "余吴湖" -> "余吴湖湖").
        if (endsWithAny(raw, "湖", "沼", "池", "浦", "海", "潟")) return raw;
        return raw + "湖";
    }

    private static String displayRiverName(String raw) {
        String mapped = RIVER_NAMES.get(raw);
        if (mapped != null) return mapped;

        // Same rule for newly-authored river names: preserve an existing hydronym suffix.
        if (endsWithAny(raw, "川", "河", "江", "溪", "渓", "沢")) return raw;
        return raw + "川";
    }

    private static boolean endsWithAny(String value, String... suffixes) {
        for (String suffix : suffixes) {
            if (value.endsWith(suffix)) return true;
        }
        return false;
    }

    public record Snapshot(String location, String sea,
                           String mountain, String hydro, String island) {
        static final Snapshot EMPTY = new Snapshot("", "", "", "", "");

        public String locationSeaLine() {
            if (location.isBlank()) return sea;
            if (sea.isBlank()) return location;
            return location + " · " + sea;
        }

        public String mountainHydroLine() {
            if (mountain.isBlank()) return hydro;
            if (hydro.isBlank()) return mountain;
            return mountain + " · " + hydro;
        }
    }

    private record OceanPoint(int x, int z, double distance) {}
    private record FallbackAnchor(String name, int x, int z) {}
    private record FallbackGeo(String name, double longitude, double latitude) {}

    // Real prefectural-government coordinates used only as a no-network startup fallback. They are
    // independent from PJHUD and are replaced by the nationwide municipality layer when it loads.
    private static final List<FallbackGeo> PREFECTURE_FALLBACKS = List.of(
            new FallbackGeo("北海道", 141.3469, 43.0642),
            new FallbackGeo("青森县", 140.7400, 40.8244),
            new FallbackGeo("岩手县", 141.1527, 39.7036),
            new FallbackGeo("宫城县", 140.8721, 38.2688),
            new FallbackGeo("秋田县", 140.1024, 39.7186),
            new FallbackGeo("山形县", 140.3633, 38.2404),
            new FallbackGeo("福岛县", 140.4678, 37.7500),
            new FallbackGeo("茨城县", 140.4468, 36.3418),
            new FallbackGeo("栃木县", 139.8836, 36.5657),
            new FallbackGeo("群马县", 139.0608, 36.3911),
            new FallbackGeo("埼玉县", 139.6489, 35.8569),
            new FallbackGeo("千叶县", 140.1233, 35.6051),
            new FallbackGeo("东京都", 139.6917, 35.6895),
            new FallbackGeo("神奈川县", 139.6425, 35.4478),
            new FallbackGeo("新潟县", 139.0236, 37.9026),
            new FallbackGeo("富山县", 137.2113, 36.6953),
            new FallbackGeo("石川县", 136.6256, 36.5947),
            new FallbackGeo("福井县", 136.2216, 36.0652),
            new FallbackGeo("山梨县", 138.5684, 35.6642),
            new FallbackGeo("长野县", 138.1810, 36.6513),
            new FallbackGeo("岐阜县", 136.7223, 35.3912),
            new FallbackGeo("静冈县", 138.3831, 34.9769),
            new FallbackGeo("爱知县", 136.9066, 35.1802),
            new FallbackGeo("三重县", 136.5086, 34.7303),
            new FallbackGeo("滋贺县", 135.8686, 35.0045),
            new FallbackGeo("京都府", 135.7556, 35.0212),
            new FallbackGeo("大阪府", 135.5200, 34.6863),
            new FallbackGeo("兵库县", 135.1830, 34.6913),
            new FallbackGeo("奈良县", 135.8327, 34.6853),
            new FallbackGeo("和歌山县", 135.1675, 34.2260),
            new FallbackGeo("鸟取县", 134.2377, 35.5039),
            new FallbackGeo("岛根县", 133.0505, 35.4723),
            new FallbackGeo("冈山县", 133.9350, 34.6618),
            new FallbackGeo("广岛县", 132.4596, 34.3963),
            new FallbackGeo("山口县", 131.4705, 34.1861),
            new FallbackGeo("德岛县", 134.5593, 34.0658),
            new FallbackGeo("香川县", 134.0434, 34.3401),
            new FallbackGeo("爱媛县", 132.7657, 33.8416),
            new FallbackGeo("高知县", 133.5311, 33.5597),
            new FallbackGeo("福冈县", 130.4183, 33.6064),
            new FallbackGeo("佐贺县", 130.2988, 33.2494),
            new FallbackGeo("长崎县", 129.8737, 32.7448),
            new FallbackGeo("熊本县", 130.7417, 32.7898),
            new FallbackGeo("大分县", 131.6126, 33.2382),
            new FallbackGeo("宫崎县", 131.4239, 31.9111),
            new FallbackGeo("鹿儿岛县", 130.5581, 31.5602),
            new FallbackGeo("冲绳县", 127.6809, 26.2124));

    private static final Map<String, String> LAKE_NAMES = buildLakeNames();
    private static final Map<String, String> RIVER_NAMES = buildRiverNames();

    private static Map<String, String> buildLakeNames() {
        Map<String, String> m = new HashMap<>();
        m.put("Akan", "阿寒湖"); m.put("Kussharo", "屈斜路湖"); m.put("Mashu", "摩周湖");
        m.put("Shikaribetsu", "然别湖"); m.put("Shikotsu", "支笏湖"); m.put("Toya", "洞爷湖");
        m.put("Saroma", "佐吕间湖"); m.put("Notoro", "能取湖"); m.put("Furen", "风莲湖");
        m.put("Abashiri", "网走湖"); m.put("Kuttara", "俱多乐湖"); m.put("Onuma", "大沼");
        m.put("Ogawara", "小川原湖"); m.put("Jusan", "十三湖"); m.put("Towada", "十和田湖");
        m.put("Tazawa", "田泽湖"); m.put("Inawashiro", "猪苗代湖"); m.put("Hibara", "桧原湖");
        m.put("Chuzenji", "中禅寺湖"); m.put("Kasumigaura", "霞浦"); m.put("Kitaura", "北浦");
        m.put("Suwa", "诹访湖"); m.put("Nojiri", "野尻湖"); m.put("Haruna", "榛名湖");
        m.put("Ashinoko", "芦之湖"); m.put("Yamanaka", "山中湖"); m.put("Kawaguchi", "河口湖");
        m.put("Sai", "西湖"); m.put("Shoji", "精进湖"); m.put("Motosu", "本栖湖");
        m.put("Hamana", "滨名湖"); m.put("Biwa", "琵琶湖"); m.put("Shinji", "宍道湖");
        m.put("Nakaumi", "中海"); m.put("Ikeda", "池田湖");
        return Map.copyOf(m);
    }

    private static Map<String, String> buildRiverNames() {
        Map<String, String> m = new HashMap<>();
        String[][] e = {
                {"Ishikari","石狩川"},{"Tokachi","十胜川"},{"Teshio","天盐川"},{"Kushiro","钏路川"},
                {"Abashiri","网走川"},{"Tokoro","常吕川"},{"Yubetsu","涌别川"},{"Shokotsu","渚滑川"},
                {"Shiribetsu","尻别川"},{"Mu","鹉川"},{"Saru","沙流川"},{"Sorachi","空知川"},{"Toyohira","丰平川"},
                {"Mabechi","马渊川"},{"Takase","高濑川"},{"Iwaki","岩木川"},{"Yoneshiro","米代川"},{"Omono","雄物川"},
                {"Koyoshi","子吉川"},{"Mogami","最上川"},{"Aka","赤川"},{"Kitakami","北上川"},{"Naruse","鸣濑川"},
                {"Natori","名取川"},{"Abukuma","阿武隈川"},{"Tone","利根川"},{"Edo","江户川"},{"Arakawa","荒川"},
                {"Sumida","隅田川"},{"Tama","多摩川"},{"Sagami","相模川"},{"Naka","那珂川"},{"Kuji","久慈川"},
                {"Tsurumi","鹤见川"},{"Kinu","鬼怒川"},{"Kokai","小贝川"},{"Watarase","渡良濑川"},{"Agatsuma","吾妻川"},
                {"Karasu","乌川"},{"Iruma","入间川"},{"Asakawa","浅川"},{"Shinano","信浓川"},{"Agano","阿贺野川"},
                {"Seki","关川"},{"Hime","姬川"},{"Kurobe","黑部川"},{"Joganji","常愿寺川"},{"Jinzu","神通川"},
                {"Sho","庄川"},{"Oyabe","小矢部川"},{"Tedori","手取川"},{"Kuzuryu","九头龙川"},{"Sai","犀川"},
                {"Uono","鱼野川"},{"Fuji","富士川"},{"Kano","狩野川"},{"Abe","安倍川"},{"Oi","大井川"},
                {"Kiku","菊川"},{"Tenryu","天龙川"},{"Toyo","丰川"},{"Yahagi","矢作川"},{"Shonai","庄内川"},
                {"Kiso","木曾川"},{"Nagara","长良川"},{"Ibi","揖斐川"},{"Suzuka","铃鹿川"},{"Kumozu","云出川"},
                {"Kushida","栉田川"},{"Miya","宫川"},{"Yodo","淀川"},{"Yura","由良川"},{"Kakogawa","加古川"},
                {"Ibo","揖保川"},{"Kino","纪之川"},{"Yamato","大和川"},{"Hida","飞騨川"},{"Katsura","桂川"},
                {"Kizu","木津川"},{"Sendai-Tottori","千代川"},{"Gono","江之川"},{"Takatsu","高津川"},{"Yoshii","吉井川"},
                {"Asahi","旭川"},{"Takahashi","高梁川"},{"Ashida","芦田川"},{"Ota","太田川"},{"Oze","小濑川"},
                {"Saba","佐波川"},{"Hii","斐伊川"},{"Ohashi","大桥川"},{"Yoshino","吉野川"},{"Naka-Tokushima","那贺川"},
                {"Doki","土器川"},{"Shigenobu","重信川"},{"Hiji","肱川"},{"Shimanto","四万十川"},{"Onga","远贺川"},
                {"Yamakuni","山国川"},{"Oita","大分川"},{"Ono","大野川"},{"Banjo","番匠川"},{"Gokase","五濑川"},
                {"Oyodo","大淀川"},{"Sendai-Kagoshima","川内川"},{"Kimotsuki","肝属川"},{"Matsuura","松浦川"},{"Rokkaku","六角川"},
                {"Chikugo","筑后川"},{"Kikuchi","菊池川"},{"Midori","绿川"},{"Shira","白川"},{"Honmyo","本明川"},
                {"Kuma","球磨川"},{"Kusu","玖珠川"}
        };
        for (String[] pair : e) m.put(pair[0], pair[1]);
        return Map.copyOf(m);
    }
}
