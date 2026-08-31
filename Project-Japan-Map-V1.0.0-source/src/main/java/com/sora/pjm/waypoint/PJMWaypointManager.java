package com.sora.pjm.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side waypoint storage.
 *
 * <p>Waypoints are separated by world/server identity so two PJ saves do not share markers.
 * Single-player uses the actual save root path rather than only the level display name; multiplayer
 * uses the current server address. This follows the same proven world/server separation principle
 * used by established map mods.</p>
 */
public final class PJMWaypointManager {
    public static final PJMWaypointManager INSTANCE = new PJMWaypointManager();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private String currentKey;
    private Path currentFile;
    private final List<PJMWaypoint> waypoints = new ArrayList<>();

    private PJMWaypointManager() {
    }

    public synchronized void ensureWorld(Minecraft minecraft) {
        String identity = worldIdentity(minecraft);
        String key = sha256Short(identity);
        if (key.equals(currentKey)) {
            return;
        }

        currentKey = key;
        Path waypointDir = FMLPaths.CONFIGDIR.get()
                .resolve("PJM")
                .resolve("waypoints");
        currentFile = waypointDir.resolve(key + ".json");

        migrateLegacySingleplayerFile(minecraft, waypointDir);

        waypoints.clear();
        load();
    }

    public synchronized List<PJMWaypoint> all(Minecraft minecraft) {
        ensureWorld(minecraft);
        return Collections.unmodifiableList(new ArrayList<>(waypoints));
    }

    public synchronized List<PJMWaypoint> visible(Minecraft minecraft) {
        ensureWorld(minecraft);
        List<PJMWaypoint> result = new ArrayList<>();
        for (PJMWaypoint waypoint : waypoints) {
            if (waypoint.enabled()) result.add(waypoint);
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized void add(Minecraft minecraft, PJMWaypoint waypoint) {
        ensureWorld(minecraft);
        waypoints.add(waypoint);
        save();
    }

    public synchronized void update(Minecraft minecraft, PJMWaypoint waypoint) {
        ensureWorld(minecraft);
        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i).id().equals(waypoint.id())) {
                waypoints.set(i, waypoint);
                save();
                return;
            }
        }
        // If the marker disappeared from disk between opening and saving the editor, treat it as
        // a normal add rather than losing the user's changes.
        waypoints.add(waypoint);
        save();
    }

    public synchronized void setEnabled(Minecraft minecraft, String waypointId, boolean enabled) {
        ensureWorld(minecraft);
        for (int i = 0; i < waypoints.size(); i++) {
            PJMWaypoint waypoint = waypoints.get(i);
            if (waypoint.id().equals(waypointId)) {
                waypoints.set(i, waypoint.withEnabled(enabled));
                save();
                return;
            }
        }
    }

    public synchronized void toggleEnabled(Minecraft minecraft, String waypointId) {
        ensureWorld(minecraft);
        for (int i = 0; i < waypoints.size(); i++) {
            PJMWaypoint waypoint = waypoints.get(i);
            if (waypoint.id().equals(waypointId)) {
                waypoints.set(i, waypoint.withEnabled(!waypoint.enabled()));
                save();
                return;
            }
        }
    }

    public synchronized PJMWaypoint find(Minecraft minecraft, String waypointId) {
        ensureWorld(minecraft);
        for (PJMWaypoint waypoint : waypoints) {
            if (waypoint.id().equals(waypointId)) return waypoint;
        }
        return null;
    }

    public synchronized void delete(Minecraft minecraft, String waypointId) {
        ensureWorld(minecraft);
        if (waypoints.removeIf(waypoint -> waypoint.id().equals(waypointId))) {
            save();
        }
    }

    public synchronized String nextDefaultName(Minecraft minecraft) {
        ensureWorld(minecraft);
        int number = waypoints.size() + 1;
        String candidate;
        do {
            candidate = "路径点 " + number++;
        } while (containsName(candidate));
        return candidate;
    }

    public synchronized void reset() {
        currentKey = null;
        currentFile = null;
        waypoints.clear();
    }

    private boolean containsName(String name) {
        for (PJMWaypoint waypoint : waypoints) {
            if (waypoint.name().equals(name)) return true;
        }
        return false;
    }

    private void load() {
        if (currentFile == null || !Files.isRegularFile(currentFile)) return;
        try {
            WaypointFile file = GSON.fromJson(
                    Files.readString(currentFile, StandardCharsets.UTF_8),
                    WaypointFile.class);
            if (file != null && file.waypoints != null) {
                Set<String> seenIds = new HashSet<>();
                for (PJMWaypoint waypoint : file.waypoints) {
                    if (waypoint == null || !seenIds.add(waypoint.id())) continue;
                    waypoints.add(waypoint);
                }
            }
        } catch (IOException | JsonSyntaxException ignored) {
            // A malformed local waypoint file must never stop the map from opening.
        }
    }

    private void save() {
        if (currentFile == null) return;
        try {
            Files.createDirectories(currentFile.getParent());
            Files.writeString(currentFile,
                    GSON.toJson(new WaypointFile(2, new ArrayList<>(waypoints))),
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private static String worldIdentity(Minecraft minecraft) {
        if (minecraft.getSingleplayerServer() != null) {
            try {
                Path saveRoot = minecraft.getSingleplayerServer()
                        .getWorldPath(LevelResource.ROOT)
                        .toAbsolutePath().normalize();
                return "singleplayer:" + saveRoot;
            } catch (RuntimeException ignored) {
                return "singleplayer:" + minecraft.getSingleplayerServer()
                        .getWorldData().getLevelName();
            }
        }

        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null) {
            // Keep the pre-rework identity byte-for-byte so multiplayer waypoint files do not move.
            return "multiplayer:" + server.ip;
        }

        return "unknown-world";
    }

    /**
     * 0.19 and earlier keyed single-player waypoint files only by the level display name. The
     * rework keys new files by the actual save path to avoid collisions between same-named saves,
     * but copies the legacy file forward once so existing user waypoints are not lost.
     */
    private void migrateLegacySingleplayerFile(Minecraft minecraft, Path waypointDir) {
        if (currentFile == null || Files.isRegularFile(currentFile)
                || minecraft.getSingleplayerServer() == null) {
            return;
        }

        String levelName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
        Path legacyFile = waypointDir.resolve(
                sha256Short("singleplayer:" + levelName) + ".json");
        if (legacyFile.equals(currentFile) || !Files.isRegularFile(legacyFile)) {
            return;
        }

        try {
            Files.createDirectories(waypointDir);
            Files.copy(legacyFile, currentFile);
        } catch (IOException ignored) {
            // If migration fails, do not block the map; the legacy file remains untouched.
        }
    }

    private static String sha256Short(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(24);
            for (int i = 0; i < Math.min(12, bytes.length); i++) {
                result.append(String.format("%02x", bytes[i] & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static final class WaypointFile {
        int version;
        List<PJMWaypoint> waypoints;

        WaypointFile(int version, List<PJMWaypoint> waypoints) {
            this.version = version;
            this.waypoints = waypoints;
        }
    }
}
