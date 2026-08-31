package com.sora.pjm.client.world;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * Single-player world actions used by both map teleport and waypoint creation.
 *
 * <p>The old implementation trusted one WORLD_SURFACE heightmap value directly. 0.12 verifies an
 * actual two-block standing space with a solid, non-fluid support block. If the heightmap candidate
 * is unsuitable it searches nearby before falling back to an above-surface Y. This prevents a bad
 * or stale heightmap candidate from putting the player inside terrain.</p>
 */
public final class PJMWorldActions {
    private static final int SEARCH_RADIUS = 48;

    private PJMWorldActions() {
    }

    /**
     * Resolves a default feet-Y for an X/Z pair on the integrated server.
     *
     * <p>Resolving an unexplored point loads/generates only that target chunk. This is deliberate:
     * PJM still does not pre-generate the national map, but a user-created physical waypoint gets
     * the same verified ground logic as teleport.</p>
     */
    public static void resolveGroundY(Minecraft minecraft, int blockX, int blockZ,
                                      IntConsumer onResolved) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            int fallback = minecraft.player == null ? 64 : Mth.floor(minecraft.player.getY());
            minecraft.execute(() -> onResolved.accept(fallback));
            return;
        }

        server.execute(() -> {
            ServerLevel level = server.overworld();
            level.getChunk(blockX >> 4, blockZ >> 4);
            int y = findSafeFeetY(level, blockX, blockZ);
            minecraft.execute(() -> onResolved.accept(y));
        });
    }

    public static boolean teleportToSurface(Minecraft minecraft,
                                            double targetX, double targetZ,
                                            Runnable afterScheduled) {
        if (minecraft.player == null) return false;

        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("screen.pjm.teleport_singleplayer_only"), true);
            return false;
        }

        UUID playerId = minecraft.player.getUUID();
        int blockX = Mth.floor(targetX);
        int blockZ = Mth.floor(targetZ);

        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return;

            ServerLevel level = server.overworld();
            level.getChunk(blockX >> 4, blockZ >> 4);
            int feetY = findSafeFeetY(level, blockX, blockZ);

            player.teleportTo(level,
                    blockX + 0.5D, feetY, blockZ + 0.5D,
                    player.getYRot(), player.getXRot());
        });

        if (afterScheduled != null) afterScheduled.run();
        return true;
    }

    /**
     * Teleports to a waypoint's stored XYZ. Newly created waypoints already receive a verified
     * surface Y; if the user later edits Y manually, that explicit value is respected.
     */
    public static boolean teleportToExact(Minecraft minecraft,
                                          double targetX, double targetY, double targetZ,
                                          Runnable afterScheduled) {
        if (minecraft.player == null) return false;

        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("screen.pjm.teleport_singleplayer_only"), true);
            return false;
        }

        UUID playerId = minecraft.player.getUUID();
        int blockX = Mth.floor(targetX);
        int blockZ = Mth.floor(targetZ);

        server.execute(() -> {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) return;

            ServerLevel level = server.overworld();
            level.getChunk(blockX >> 4, blockZ >> 4);

            double requestedY = Mth.clamp(
                    targetY,
                    level.getMinBuildHeight() + 1.0D,
                    level.getMaxBuildHeight() - 2.0D);

            int requestedFeetY = Mth.floor(requestedY);
            double safeY = canStand(level, blockX, requestedFeetY, blockZ)
                    ? requestedY
                    : findSafeFeetY(level, blockX, blockZ);

            player.teleportTo(level,
                    targetX, safeY, targetZ,
                    player.getYRot(), player.getXRot());
        });

        if (afterScheduled != null) afterScheduled.run();
        return true;
    }

    /**
     * Finds the highest genuinely standable feet-Y in this world column.
     *
     * <p>0.12/0.13 started from a heightmap and then searched nearby. In unusual generated columns
     * that could still select a cave surface. 0.14 scans from the top of the buildable world
     * downward and accepts the first place with a real support block plus two collision-free,
     * fluid-free body blocks. A lower cave can therefore never win while a higher surface exists.</p>
     */
    static int findSafeFeetY(ServerLevel level, int x, int z) {
        int minFeetY = level.getMinBuildHeight() + 1;
        int maxFeetY = level.getMaxBuildHeight() - 2;

        for (int feetY = maxFeetY; feetY >= minFeetY; feetY--) {
            if (canStand(level, x, feetY, z)) {
                return feetY;
            }
        }

        // Water / pathological column fallback: WORLD_SURFACE is first-free above the topmost
        // surface/fluid. Find a two-block clear column at or above it so the player is never placed
        // underground even when no dry solid support exists at this exact X/Z.
        int surface = Mth.clamp(
                level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z),
                minFeetY, maxFeetY);
        for (int feetY = surface;
             feetY <= Math.min(maxFeetY, surface + 64);
             feetY++) {
            if (isBodySpaceClear(level, x, feetY, z)) {
                return feetY;
            }
        }

        return maxFeetY;
    }

    private static boolean canStand(ServerLevel level, int x, int y, int z) {
        if (!isBodySpaceClear(level, x, y, z)) return false;

        BlockPos below = new BlockPos(x, y - 1, z);
        BlockState support = level.getBlockState(below);

        // Avoid treating tree crowns as "ground". Logs/constructed roofs remain valid surfaces,
        // but leaf blocks are deliberately skipped.
        if (support.is(BlockTags.LEAVES)) return false;

        return support.getFluidState().isEmpty()
                && !support.getCollisionShape(level, below).isEmpty();
    }

    private static boolean isBodySpaceClear(ServerLevel level, int x, int y, int z) {
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.above();

        BlockState feetState = level.getBlockState(feet);
        BlockState headState = level.getBlockState(head);

        return feetState.getFluidState().isEmpty()
                && headState.getFluidState().isEmpty()
                && feetState.getCollisionShape(level, feet).isEmpty()
                && headState.getCollisionShape(level, head).isEmpty();
    }
}
