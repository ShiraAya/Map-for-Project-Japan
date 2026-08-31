package com.sora.pjm.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Chunk-independent PJM renderer.
 *
 * <p>When the integer source zoom changes, 0.9 keeps the last resolved zoom level underneath the
 * new tiles until the new level has loaded. This removes the "blank map rebuild" feeling while the
 * cache is being filled.</p>
 */
public final class PJMMapRenderer implements AutoCloseable {
    private final PJMMapSource mapSource;
    private final PJMTextureCache textureCache;

    private double centerWorldX;
    private double centerWorldZ;
    private double zoom;

    private int activeBaseZoom = -1;
    private int fallbackBaseZoom = -1;

    public PJMMapRenderer(Minecraft minecraft, PJMMapSource mapSource, int maxGpuTiles) {
        this.mapSource = mapSource;
        this.textureCache = new PJMTextureCache(minecraft, mapSource, maxGpuTiles);
        PJMMapHeader h = mapSource.header();
        centerWorldX = (h.minWorldX() + h.maxWorldX()) * 0.5;
        centerWorldZ = (h.minWorldZ() + h.maxWorldZ()) * 0.5;
        zoom = h.minZoom();
    }

    public void render(GuiGraphics graphics, int left, int top, int width, int height) {
        if (width <= 0 || height <= 0) return;

        PJMMapHeader h = mapSource.header();
        graphics.fill(left, top, left + width, top + height, h.backgroundArgb());

        int baseZoom = clamp((int)Math.round(zoom), h.minZoom(), h.maxZoom());
        if (activeBaseZoom < 0) {
            activeBaseZoom = baseZoom;
        } else if (baseZoom != activeBaseZoom) {
            fallbackBaseZoom = activeBaseZoom;
            activeBaseZoom = baseZoom;
        }

        graphics.enableScissor(left, top, left + width, top + height);

        // Draw the last zoom level from GPU cache only. No new work is queued for fallback tiles.
        if (fallbackBaseZoom >= h.minZoom()
                && fallbackBaseZoom <= h.maxZoom()
                && fallbackBaseZoom != baseZoom) {
            renderLevel(graphics, left, top, width, height, fallbackBaseZoom, false);
        }

        boolean allResolved = renderLevel(
                graphics, left, top, width, height, baseZoom, true);

        if (allResolved) {
            fallbackBaseZoom = -1;
        }

        graphics.disableScissor();
    }

    private boolean renderLevel(GuiGraphics graphics,
                                int left, int top, int width, int height,
                                int level, boolean requestMissing) {
        PJMMapHeader h = mapSource.header();
        double levelScale = Math.pow(2.0D, zoom - level);
        int tileSize = h.tileSize();
        int tilesPerAxis = 1 << level;
        double mapPixels = tileSize * (double) tilesPerAxis;

        double centerPxX = worldToMapPixelX(centerWorldX, mapPixels);
        double centerPxY = worldToMapPixelY(centerWorldZ, mapPixels);
        double visibleMapWidth = width / levelScale;
        double visibleMapHeight = height / levelScale;
        double mapLeft = centerPxX - visibleMapWidth * 0.5D;
        double mapTop = centerPxY - visibleMapHeight * 0.5D;

        int minTileX = Math.max(0, (int)Math.floor(mapLeft / tileSize) - 1);
        int minTileY = Math.max(0, (int)Math.floor(mapTop / tileSize) - 1);
        int maxTileX = Math.min(tilesPerAxis - 1,
                (int)Math.floor((mapLeft + visibleMapWidth) / tileSize) + 1);
        int maxTileY = Math.min(tilesPerAxis - 1,
                (int)Math.floor((mapTop + visibleMapHeight) / tileSize) + 1);

        boolean allResolved = true;

        for (int tileY = minTileY; tileY <= maxTileY; tileY++) {
            for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                PJMTileKey key = new PJMTileKey(level, tileX, tileY);
                ResourceLocation texture = requestMissing
                        ? textureCache.get(key)
                        : textureCache.getIfPresent(key);

                if (requestMissing && !textureCache.isResolved(key)) {
                    allResolved = false;
                }

                if (texture == null) continue;

                double sx = left + (tileX * (double)tileSize - mapLeft) * levelScale;
                double sy = top + (tileY * (double)tileSize - mapTop) * levelScale;

                graphics.pose().pushPose();
                graphics.pose().translate((float)sx, (float)sy, 0.0F);
                graphics.pose().scale((float)levelScale, (float)levelScale, 1.0F);
                graphics.blit(texture, 0, 0, 0, 0,
                        tileSize, tileSize, tileSize, tileSize);
                graphics.pose().popPose();
            }
        }

        return allResolved;
    }

    public void panPixels(double dragPixelsX, double dragPixelsY) {
        PJMMapHeader h = mapSource.header();
        double fullMapPixels = h.tileSize() * Math.pow(2.0D, zoom);
        centerWorldX -= dragPixelsX * h.worldWidth() / fullMapPixels;
        centerWorldZ -= dragPixelsY * h.worldHeight() / fullMapPixels;
        clampCenter();
    }

    public void zoomAt(double mouseX, double mouseY, double wheelDelta,
                       int left, int top, int width, int height) {
        WorldPoint before = screenToWorld(mouseX, mouseY, left, top, width, height);
        PJMMapHeader h = mapSource.header();
        zoom = clamp(zoom + wheelDelta * 0.25D, h.minZoom(), h.maxZoom());
        WorldPoint after = screenToWorld(mouseX, mouseY, left, top, width, height);
        centerWorldX += before.x() - after.x();
        centerWorldZ += before.z() - after.z();
        clampCenter();
    }

    public WorldPoint screenToWorld(double screenX, double screenY,
                                    int left, int top, int width, int height) {
        PJMMapHeader h = mapSource.header();
        double fullMapPixels = h.tileSize() * Math.pow(2.0D, zoom);
        double worldPerPixelX = h.worldWidth() / fullMapPixels;
        double worldPerPixelZ = h.worldHeight() / fullMapPixels;
        double dx = screenX - (left + width * 0.5D);
        double dy = screenY - (top + height * 0.5D);
        return new WorldPoint(centerWorldX + dx * worldPerPixelX,
                centerWorldZ + dy * worldPerPixelZ);
    }

    public ScreenPoint worldToScreen(double worldX, double worldZ,
                                     int left, int top, int width, int height) {
        PJMMapHeader h = mapSource.header();
        double fullMapPixels = h.tileSize() * Math.pow(2.0D, zoom);
        double pixelsPerWorldX = fullMapPixels / h.worldWidth();
        double pixelsPerWorldZ = fullMapPixels / h.worldHeight();
        return new ScreenPoint(
                left + width * 0.5D + (worldX - centerWorldX) * pixelsPerWorldX,
                top + height * 0.5D + (worldZ - centerWorldZ) * pixelsPerWorldZ);
    }

    public void centerOn(double worldX, double worldZ) {
        centerWorldX = worldX;
        centerWorldZ = worldZ;
        clampCenter();
    }

    public void fitToViewport(int width, int height) {
        PJMMapHeader h = mapSource.header();
        double fitPixels = Math.max(1.0D, Math.min(width, height));
        double rawZoom = Math.log(fitPixels / h.tileSize()) / Math.log(2.0D);
        zoom = clamp(rawZoom, h.minZoom(), h.maxZoom());
    }

    public double zoom() {
        return zoom;
    }

    public void setZoom(double zoom) {
        PJMMapHeader h = mapSource.header();
        this.zoom = clamp(zoom, h.minZoom(), h.maxZoom());
    }

    public void setZoomForWorldSpan(int viewportPixels, double worldSpanBlocks) {
        PJMMapHeader h = mapSource.header();
        if (viewportPixels <= 0 || worldSpanBlocks <= 0.0D) return;

        double pixelsPerWorld = viewportPixels / worldSpanBlocks;
        double fullMapPixels = pixelsPerWorld * h.worldWidth();
        double targetZoom = Math.log(fullMapPixels / h.tileSize()) / Math.log(2.0D);
        zoom = clamp(targetZoom, h.minZoom(), h.maxZoom());
    }

    public PJMMapHeader header() {
        return mapSource.header();
    }

    public int pendingTileCount() {
        return textureCache.pendingCount();
    }

    private double worldToMapPixelX(double worldX, double mapPixels) {
        PJMMapHeader h = mapSource.header();
        return (worldX - h.minWorldX()) / h.worldWidth() * mapPixels;
    }

    private double worldToMapPixelY(double worldZ, double mapPixels) {
        PJMMapHeader h = mapSource.header();
        return (worldZ - h.minWorldZ()) / h.worldHeight() * mapPixels;
    }

    private void clampCenter() {
        PJMMapHeader h = mapSource.header();
        centerWorldX = clamp(centerWorldX, h.minWorldX(), h.maxWorldX());
        centerWorldZ = clamp(centerWorldZ, h.minWorldZ(), h.maxWorldZ());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void close() throws java.io.IOException {
        textureCache.close();
    }

    public record WorldPoint(double x, double z) {
    }

    public record ScreenPoint(double x, double y) {
    }
}
