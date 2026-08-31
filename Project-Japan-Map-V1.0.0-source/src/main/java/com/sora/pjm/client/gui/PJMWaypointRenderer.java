package com.sora.pjm.client.gui;

import com.sora.pjm.waypoint.PJMWaypoint;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Shared Xaero-style waypoint icon renderer for the minimap and full map. */
public final class PJMWaypointRenderer {
    private PJMWaypointRenderer() {
    }

    public static void renderIcon(GuiGraphics graphics, Font font,
                                  PJMWaypoint waypoint,
                                  int centerX, int centerY,
                                  int size) {
        renderIcon(graphics, font, waypoint, centerX, centerY, size, 1.0F);
    }

    public static void renderIcon(GuiGraphics graphics, Font font,
                                  PJMWaypoint waypoint,
                                  int centerX, int centerY,
                                  int size, float alpha) {
        int half = Math.max(3, size / 2);
        int left = centerX - half;
        int top = centerY - half;
        int right = centerX + half + 1;
        int bottom = centerY + half + 1;

        int a = Math.max(0, Math.min(255, Math.round(255.0F * alpha)));
        int borderA = Math.max(0, Math.min(255, Math.round(220.0F * alpha)));
        int rgb = waypoint.colorRgb();
        int fill = (a << 24) | rgb;
        int border = (borderA << 24) | 0x111111;

        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, border);
        graphics.fill(left, top, right, bottom, fill);

        String symbol = waypoint.symbol();
        if (symbol == null || symbol.isBlank() || size < 9) return;

        int textColor = contrastTextColor(rgb, a);
        float scale = symbol.codePointCount(0, symbol.length()) <= 1 ? 0.72F : 0.56F;
        int rawWidth = font.width(symbol);
        float drawX = centerX - rawWidth * scale * 0.5F;
        float drawY = centerY - font.lineHeight * scale * 0.5F + 0.5F;

        graphics.pose().pushPose();
        graphics.pose().translate(drawX, drawY, 10.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, symbol, 0, 0, textColor, false);
        graphics.pose().popPose();
    }

    private static int contrastTextColor(int rgb, int alpha) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        double luminance = 0.2126D * r + 0.7152D * g + 0.0722D * b;
        int textRgb = luminance >= 150.0D ? 0x101010 : 0xFFFFFF;
        return (alpha << 24) | textRgb;
    }
}
