package com.sora.pjm.map;

public record PJMMapHeader(
        int formatVersion,
        int tileSize,
        int minZoom,
        int maxZoom,
        double minWorldX,
        double minWorldZ,
        double maxWorldX,
        double maxWorldZ,
        long indexOffset,
        int tileCount,
        int backgroundArgb
) {
    public double worldWidth() {
        return maxWorldX - minWorldX;
    }

    public double worldHeight() {
        return maxWorldZ - minWorldZ;
    }
}
