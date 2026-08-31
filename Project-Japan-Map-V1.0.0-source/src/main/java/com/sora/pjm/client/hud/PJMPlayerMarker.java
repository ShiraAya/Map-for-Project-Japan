package com.sora.pjm.client.hud;

import com.mojang.math.Axis;
import com.sora.pjm.PJM;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** Shared north-up directional player marker for the minimap and full map. */
public final class PJMPlayerMarker {
    private static final ResourceLocation TEXTURE = new ResourceLocation(
            PJM.MOD_ID, "textures/gui/player_marker.png");
    private static final int TEXTURE_SIZE = 64;

    private PJMPlayerMarker() {
    }

    /**
     * Renders a smooth navigation-style marker whose texture points toward screen north.
     * Minecraft yaw 180 degrees is north, therefore the north-up map rotation is yaw + 180.
     */
    public static void render(GuiGraphics graphics, double centerX, double centerY,
                              float playerYaw, float scale) {
        graphics.pose().pushPose();
        graphics.pose().translate((float) centerX, (float) centerY, 250.0f);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(playerYaw + 180.0f));

        // 64 px source rendered at ~20 GUI px for scale=1.0. Scaling the pose instead of
        // cropping UVs keeps the entire anti-aliased texture visible at every HUD size.
        float textureScale = 0.32f * scale;
        graphics.pose().scale(textureScale, textureScale, 1.0f);
        graphics.blit(TEXTURE,
                -TEXTURE_SIZE / 2, -TEXTURE_SIZE / 2,
                0, 0,
                TEXTURE_SIZE, TEXTURE_SIZE,
                TEXTURE_SIZE, TEXTURE_SIZE);
        graphics.pose().popPose();
    }
}
