package com.sora.pjm.client.hud;

import com.sora.pjm.client.gui.PJMWaypointRenderer;
import com.sora.pjm.waypoint.PJMWaypoint;
import com.sora.pjm.waypoint.PJMWaypointManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Xaero-style in-world waypoint overlay.
 *
 * <p>Unlike the old PJM edge-clamped crosshair implementation, a waypoint is rendered only when
 * its actual direction is inside the camera viewport. All visible waypoints keep their colored
 * icon/initials and name. By default only the waypoint closest to the crosshair gets a distance
 * line, matching the long-established low-clutter behavior exposed by Xaero's waypoint settings.</p>
 */
public final class PJMWorldWaypointOverlay implements IGuiOverlay {
    public static final PJMWorldWaypointOverlay INSTANCE = new PJMWorldWaypointOverlay();

    private static final int SCREEN_MARGIN = 12;

    private PJMWorldWaypointOverlay() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick,
                       int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null
                || minecraft.level == null
                || minecraft.options.hideGui
                || minecraft.screen != null
                || minecraft.level.dimension() != Level.OVERWORLD) {
            return;
        }

        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cameraPos = camera.getPosition();

        double verticalFov = Math.toRadians(minecraft.options.fov().get());
        double aspect = screenWidth / (double)Math.max(1, screenHeight);
        double horizontalFov = 2.0D * Math.atan(
                Math.tan(verticalFov * 0.5D) * aspect);

        List<VisibleWaypoint> visible = new ArrayList<>();
        for (PJMWaypoint waypoint : PJMWaypointManager.INSTANCE.visible(minecraft)) {
            VisibleWaypoint projected = project(
                    waypoint, cameraPos,
                    camera.getYRot(), camera.getXRot(),
                    horizontalFov, verticalFov,
                    screenWidth, screenHeight);
            if (projected != null) visible.add(projected);
        }

        if (visible.isEmpty()) return;

        VisibleWaypoint distanceTarget = visible.stream()
                .min(Comparator.comparingDouble(VisibleWaypoint::centerDistanceSq))
                .orElse(null);

        // Draw far markers first so a closer marker is not hidden behind one at the same angle.
        visible.sort(Comparator.comparingDouble(VisibleWaypoint::distance).reversed());
        for (VisibleWaypoint item : visible) {
            renderMarker(graphics, minecraft, item,
                    item == distanceTarget);
        }
    }

    private static VisibleWaypoint project(PJMWaypoint waypoint,
                                            Vec3 cameraPos,
                                            double cameraYaw,
                                            double cameraPitch,
                                            double horizontalFov,
                                            double verticalFov,
                                            int screenWidth,
                                            int screenHeight) {
        double dx = waypoint.x() - cameraPos.x;
        double dy = waypoint.y() + 1.5D - cameraPos.y;
        double dz = waypoint.z() - cameraPos.z;

        double horizontalDistance = Math.hypot(dx, dz);
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.5D) return null;

        // Minecraft: yaw 0 -> +Z, yaw +90 -> -X.
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double targetPitch = -Math.toDegrees(
                Math.atan2(dy, Math.max(0.000001D, horizontalDistance)));

        double yawDiff = Math.toRadians(Mth.wrapDegrees((float)(targetYaw - cameraYaw)));
        double pitchDiff = Math.toRadians(Mth.wrapDegrees((float)(targetPitch - cameraPitch)));

        double halfHFov = horizontalFov * 0.5D;
        double halfVFov = verticalFov * 0.5D;

        // Do not invent an edge navigation marker. Established map mods render the waypoint in its
        // actual world direction; if that direction is outside the viewport, the icon is absent.
        if (Math.abs(yawDiff) >= halfHFov || Math.abs(pitchDiff) >= halfVFov) {
            return null;
        }

        double ndcX = Math.tan(yawDiff) / Math.tan(halfHFov);
        double ndcY = Math.tan(pitchDiff) / Math.tan(halfVFov);

        int x = (int)Math.round(screenWidth * 0.5D * (1.0D + ndcX));
        int y = (int)Math.round(screenHeight * 0.5D * (1.0D + ndcY));

        if (x < SCREEN_MARGIN || x > screenWidth - SCREEN_MARGIN
                || y < SCREEN_MARGIN || y > screenHeight - SCREEN_MARGIN) {
            return null;
        }

        double cx = x - screenWidth * 0.5D;
        double cy = y - screenHeight * 0.5D;
        return new VisibleWaypoint(waypoint, x, y, distance, cx * cx + cy * cy);
    }

    private static void renderMarker(GuiGraphics graphics,
                                     Minecraft minecraft,
                                     VisibleWaypoint item,
                                     boolean showDistance) {
        PJMWaypoint waypoint = item.waypoint();
        int x = item.x();
        int y = item.y();

        PJMWaypointRenderer.renderIcon(
                graphics, minecraft.font, waypoint, x, y, 11);

        String name = waypoint.name();
        int nameX = x - minecraft.font.width(name) / 2;
        graphics.drawString(minecraft.font, name,
                nameX, y - 17, 0xFFFFFFFF, true);

        if (showDistance) {
            String dist = formatDistance(item.distance());
            int distX = x - minecraft.font.width(dist) / 2;
            graphics.drawString(minecraft.font, dist,
                    distX, y + 9, 0xFFD7DEE2, true);
        }
    }

    private static String formatDistance(double distance) {
        return distance >= 1000.0D
                ? String.format(Locale.ROOT, "%.1f km", distance / 1000.0D)
                : Math.round(distance) + " m";
    }

    private record VisibleWaypoint(PJMWaypoint waypoint,
                                   int x, int y,
                                   double distance,
                                   double centerDistanceSq) {
    }
}
