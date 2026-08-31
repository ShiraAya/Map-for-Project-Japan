package com.sora.pjm.client.hud;

import com.sora.pjm.client.gui.PJMMapScreen;
import com.sora.pjm.client.gui.PJMWaypointRenderer;
import com.sora.pjm.map.PJMMapManager;
import com.sora.pjm.map.PJMMapRenderer;
import com.sora.pjm.map.PJMGeographyService;
import com.sora.pjm.waypoint.PJMWaypoint;
import com.sora.pjm.waypoint.PJMWaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.io.IOException;

/**
 * Always-on, chunk-independent PJM minimap.
 *
 * The minimap uses exactly the same .pjmap base map as the full-screen map. It never reads
 * generated chunks, so unexplored PJ terrain is visible immediately.
 */
public final class PJMMinimapOverlay implements IGuiOverlay {
    public static final PJMMinimapOverlay INSTANCE = new PJMMinimapOverlay();

    // GUI pixels, so the physical size follows Minecraft GUI Scale naturally.
    private static final int SIZE = 78;
    private static final int MARGIN = 6;
    private static final int BORDER = 2;
    private static final double DEFAULT_WORLD_SPAN_BLOCKS = 1536.0;

    /**
     * Z cycles toward a closer view. After the closest level it wraps to the farthest.
     * The default 1536-block span is included as one of the normal steps.
     */
    private static final double[] ZOOM_SPANS = {
            24576.0, 12288.0, 6144.0, 3072.0,
            1536.0, 768.0, 384.0, 192.0
    };

    private static final float COORDINATE_SCALE = 0.68F;

    private PJMMapRenderer map;
    private double worldSpanBlocks = DEFAULT_WORLD_SPAN_BLOCKS;
    private boolean visible = true;
    private String loadError;

    private PJMMinimapOverlay() {
    }

    public void toggleVisible() {
        visible = !visible;
    }

    public boolean isVisible() {
        return visible;
    }

    /** Releases the persistent HUD renderer when leaving a world. */
    public void reset() {
        if (map != null) {
            try {
                map.close();
            } catch (Exception ignored) {
            }
            map = null;
        }
        loadError = null;
    }

    @Override
    public void render(net.minecraftforge.client.gui.overlay.ForgeGui gui,
                       GuiGraphics graphics, float partialTick,
                       int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!visible || minecraft.player == null || minecraft.level == null || minecraft.options.hideGui) {
            return;
        }
        if (minecraft.screen != null || minecraft.screen instanceof PJMMapScreen) {
            return;
        }

        ensureLoaded(minecraft);
        if (map == null) {
            renderError(graphics, screenWidth);
            return;
        }

        double playerX = Mth.lerp(partialTick, minecraft.player.xo, minecraft.player.getX());
        double playerY = Mth.lerp(partialTick, minecraft.player.yo, minecraft.player.getY());
        double playerZ = Mth.lerp(partialTick, minecraft.player.zo, minecraft.player.getZ());
        map.centerOn(playerX, playerZ);
        map.setZoomForWorldSpan(SIZE, worldSpanBlocks);

        int left = screenWidth - MARGIN - SIZE;
        int top = MARGIN;
        int right = left + SIZE;
        int bottom = top + SIZE;

        // Outer border / subtle backing.
        graphics.fill(left - BORDER - 1, top - BORDER - 1,
                right + BORDER + 1, bottom + BORDER + 1, 0x99000000);
        graphics.fill(left - BORDER, top - BORDER, right + BORDER, bottom + BORDER, 0xFFE8E8E8);
        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF1C2428);

        map.render(graphics, left, top, SIZE, SIZE);
        renderWaypoints(graphics, minecraft, left, top);

        // Player stays exactly at the minimap center; north is always screen-up.
        PJMPlayerMarker.render(graphics, left + SIZE * 0.5, top + SIZE * 0.5,
                minecraft.player.getYRot(), 0.78f);

        // Tiny compass and coordinates, intentionally kept compact.
        graphics.drawCenteredString(minecraft.font, "N", left + SIZE / 2, top + 2, 0xFFFFFFFF);
        // Vanilla-style XYZ order, intentionally without labels. The coordinate row is rendered
        // smaller than normal HUD text so it no longer dominates the minimap.
        String coordinates = String.format("%d %d %d",
                Mth.floor(playerX), Mth.floor(playerY), Mth.floor(playerZ));
        int centerX = left + SIZE / 2;
        int textY = bottom + 3;
        drawCenteredScaled(graphics, minecraft, coordinates, centerX, textY,
                COORDINATE_SCALE, 0xFFFFFFFF);

        PJMGeographyService.Snapshot geo = PJMGeographyService.INSTANCE.snapshot();
        int lineStep = 7;
        int nextY = textY + lineStep;
        String locationSea = geo.locationSeaLine();
        if (!locationSea.isBlank()) {
            drawCenteredScaled(graphics, minecraft, locationSea, centerX, nextY,
                    0.58F, 0xFFFFFFFF);
            nextY += lineStep;
        }
        String mountainHydro = geo.mountainHydroLine();
        if (!mountainHydro.isBlank()) {
            drawCenteredScaled(graphics, minecraft, mountainHydro, centerX, nextY,
                    0.58F, 0xFFFFFFFF);
            nextY += lineStep;
        }
        if (!geo.island().isBlank()) {
            drawCenteredScaled(graphics, minecraft, geo.island(), centerX, nextY,
                    0.58F, 0xFFFFFFFF);
        }
    }

    private static void drawCenteredScaled(GuiGraphics graphics, Minecraft minecraft,
                                           String text, int centerX, int y,
                                           float baseScale, int color) {
        if (text == null || text.isBlank()) return;
        int rawWidth = minecraft.font.width(text);
        float maxWidth = 118.0F;
        float scale = rawWidth <= 0 ? baseScale
                : Math.min(baseScale, maxWidth / rawWidth);
        float scaledWidth = rawWidth * scale;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX - scaledWidth * 0.5F, y, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(minecraft.font, text, 0, 0, color, true);
        graphics.pose().popPose();
    }

    private void renderWaypoints(GuiGraphics graphics, Minecraft minecraft,
                                 int left, int top) {
        for (PJMWaypoint waypoint : PJMWaypointManager.INSTANCE.visible(minecraft)) {
            PJMMapRenderer.ScreenPoint point = map.worldToScreen(
                    waypoint.x(), waypoint.z(), left, top, SIZE, SIZE);
            int x = (int)Math.round(point.x());
            int y = (int)Math.round(point.y());
            if (x < left + 4 || x >= left + SIZE - 4
                    || y < top + 4 || y >= top + SIZE - 4) continue;

            PJMWaypointRenderer.renderIcon(
                    graphics, minecraft.font, waypoint, x, y, 9);
        }
    }

    /**
     * Cycles the minimap through fixed world-span levels with the Z key.
     *
     * <p>Each press zooms one step closer. Pressing Z at the closest 192-block level wraps to
     * the farthest 24576-block view. Fixed steps make the behaviour predictable and require no
     * mouse cursor while normal gameplay has the pointer captured for camera control.</p>
     */
    public void cycleZoom() {
        int current = nearestZoomIndex(worldSpanBlocks);
        int next = current + 1;
        if (next >= ZOOM_SPANS.length) {
            next = 0;
        }
        worldSpanBlocks = ZOOM_SPANS[next];
    }

    private static int nearestZoomIndex(double span) {
        int best = 0;
        double bestDistance = Math.abs(ZOOM_SPANS[0] - span);
        for (int i = 1; i < ZOOM_SPANS.length; i++) {
            double distance = Math.abs(ZOOM_SPANS[i] - span);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private void ensureLoaded(Minecraft minecraft) {
        if (map != null || loadError != null) {
            return;
        }
        try {
            PJMMapManager.MapSelection selection = PJMMapManager.selectMap();
            map = new PJMMapRenderer(minecraft, selection.source(), 48);
        } catch (IOException e) {
            loadError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private void renderError(GuiGraphics graphics, int screenWidth) {
        if (loadError == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        String text = "PJM !";
        int width = minecraft.font.width(text);
        int x = screenWidth - MARGIN - width - 4;
        graphics.fill(x - 2, MARGIN, screenWidth - MARGIN, MARGIN + 11, 0xB0000000);
        graphics.drawString(minecraft.font, text, x, MARGIN + 2, 0xFFFF6B6B, false);
    }
}
