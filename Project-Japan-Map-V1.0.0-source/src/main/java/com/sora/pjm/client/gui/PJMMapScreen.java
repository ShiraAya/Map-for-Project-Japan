package com.sora.pjm.client.gui;

import com.sora.pjm.client.hud.PJMPlayerMarker;
import com.sora.pjm.client.world.PJMWorldActions;
import com.sora.pjm.map.PJMMapManager;
import com.sora.pjm.map.PJMMapRenderer;
import com.sora.pjm.waypoint.PJMWaypoint;
import com.sora.pjm.waypoint.PJMWaypointManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;

public final class PJMMapScreen extends Screen {
    private static final double DEFAULT_OPEN_ZOOM = 5.0D;
    private static final int HELP_X = 8;
    private static final int HELP_Y = 8;
    private static final int HELP_CLOSE_SIZE = 12;
    private static final int CONTEXT_WIDTH = 104;
    private static final int CONTEXT_ROW_HEIGHT = 20;
    private static final int WAYPOINT_HIT_RADIUS = 10;
    private static boolean helpPanelVisible = false;

    private PJMMapRenderer map;
    private PJMMapManager.MapSelection selection;
    private String loadError;

    private boolean contextMenuOpen;
    private int contextMenuX;
    private int contextMenuY;
    private PJMMapRenderer.WorldPoint contextTarget;
    private PJMWaypoint contextWaypoint;
    private boolean resolvingWaypointGround;

    public PJMMapScreen() {
        super(Component.translatable("screen.pjm.title"));
    }

    @Override
    protected void init() {
        super.init();

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.waypoint.list_button"),
                        button -> {
                            if (minecraft != null) {
                                minecraft.setScreen(new PJMWaypointListScreen(this));
                            }
                        })
                .bounds(Math.max(8, width - 98), 8, 90, 20)
                .build());

        if (map != null || loadError != null) {
            return;
        }

        try {
            PJMWaypointManager.INSTANCE.ensureWorld(Minecraft.getInstance());
            selection = PJMMapManager.selectMap();
            map = new PJMMapRenderer(Minecraft.getInstance(), selection.source(), 96);
            map.setZoom(DEFAULT_OPEN_ZOOM);
            if (minecraft != null && minecraft.player != null) {
                map.centerOn(minecraft.player.getX(), minecraft.player.getZ());
            }
        } catch (IOException e) {
            loadError = e.getMessage();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF172127);

        if (map == null) {
            Component text = loadError == null
                    ? Component.translatable("screen.pjm.loading")
                    : Component.translatable("screen.pjm.error", loadError);
            graphics.drawCenteredString(font, text, width / 2, height / 2, 0xFFFFFFFF);
            return;
        }

        map.render(graphics, 0, 0, width, height);
        renderWaypoints(graphics);
        renderPlayer(graphics);
        renderCursorPanel(graphics, mouseX, mouseY);
        if (helpPanelVisible) {
            renderHelpPanel(graphics);
        } else {
            renderHelpRestoreButton(graphics);
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        PJMWaypoint hoveredWaypoint = findWaypointAt(mouseX, mouseY);
        if (!contextMenuOpen && hoveredWaypoint != null) {
            String tip = String.format(java.util.Locale.ROOT, "%s  [%.0f, %.0f, %.0f]",
                    hoveredWaypoint.name(), hoveredWaypoint.x(),
                    hoveredWaypoint.y(), hoveredWaypoint.z());
            graphics.renderTooltip(font, Component.literal(tip), mouseX, mouseY);
        }

        if (contextMenuOpen) {
            renderContextMenu(graphics, mouseX, mouseY);
        }

        if (resolvingWaypointGround) {
            Component resolving = Component.translatable("screen.pjm.waypoint.resolving_ground");
            int w = font.width(resolving) + 12;
            graphics.fill(width / 2 - w / 2, height / 2 - 8,
                    width / 2 + w / 2, height / 2 + 8, 0xC0000000);
            graphics.drawCenteredString(font, resolving, width / 2,
                    height / 2 - 4, 0xFFFFFFFF);
        }
    }

    private void renderCursorPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        PJMMapRenderer.WorldPoint cursor = map.screenToWorld(mouseX, mouseY, 0, 0, width, height);
        String text = String.format("X %.0f   Z %.0f   zoom %.2f", cursor.x(), cursor.z(), map.zoom());
        int panelWidth = font.width(text) + 12;
        int x = 8;
        int y = height - 28;
        graphics.fill(x, y, x + panelWidth, height - 8, 0xB0000000);
        graphics.drawString(font, text, x + 6, y + 6, 0xFFF4F4F4, false);
    }

    private void renderHelpPanel(GuiGraphics graphics) {
        Component help = Component.translatable("screen.pjm.help");
        String sourceName = selection == null ? "" : selection.source().displayName();
        Component sourceText = Component.translatable("screen.pjm.map_source", sourceName);
        int pending = map == null ? 0 : map.pendingTileCount();
        Component generating = Component.translatable("screen.pjm.generating_tiles", pending);

        int contentWidth = Math.max(font.width(help), font.width(sourceText));
        if (pending > 0) {
            contentWidth = Math.max(contentWidth, font.width(generating));
        }

        // Reserve a compact square for the × button without making the panel much wider.
        int panelWidth = contentWidth + 12 + HELP_CLOSE_SIZE;
        int panelHeight = pending > 0 ? 49 : 35;

        graphics.fill(HELP_X, HELP_Y, HELP_X + panelWidth, HELP_Y + panelHeight, 0xA0000000);
        graphics.drawString(font, help, HELP_X + 6, HELP_Y + 6, 0xFFFFFFFF, false);

        if (selection != null) {
            graphics.drawString(font, sourceText, HELP_X + 6, HELP_Y + 20,
                    selection.demo() ? 0xFFFFD36A : 0xFFEAEAEA, false);
        }
        if (pending > 0) {
            graphics.drawString(font, generating, HELP_X + 6, HELP_Y + 34,
                    0xFFFFD36A, false);
        }

        int closeX = HELP_X + panelWidth - HELP_CLOSE_SIZE;
        graphics.fill(closeX, HELP_Y, HELP_X + panelWidth, HELP_Y + HELP_CLOSE_SIZE, 0x66000000);
        graphics.drawCenteredString(font, "×",
                closeX + HELP_CLOSE_SIZE / 2, HELP_Y + 2, 0xFFFFFFFF);
    }

    private void renderHelpRestoreButton(GuiGraphics graphics) {
        graphics.fill(HELP_X, HELP_Y, HELP_X + 14, HELP_Y + 14, 0xA0000000);
        graphics.drawCenteredString(font, "?", HELP_X + 7, HELP_Y + 3, 0xFFFFFFFF);
    }

    private int helpPanelWidth() {
        Component help = Component.translatable("screen.pjm.help");
        String sourceName = selection == null ? "" : selection.source().displayName();
        Component sourceText = Component.translatable("screen.pjm.map_source", sourceName);
        int pending = map == null ? 0 : map.pendingTileCount();
        Component generating = Component.translatable("screen.pjm.generating_tiles", pending);

        int contentWidth = Math.max(font.width(help), font.width(sourceText));
        if (pending > 0) {
            contentWidth = Math.max(contentWidth, font.width(generating));
        }
        return contentWidth + 12 + HELP_CLOSE_SIZE;
    }

    private boolean isOverHelpClose(double mouseX, double mouseY) {
        int panelWidth = helpPanelWidth();
        int closeX = HELP_X + panelWidth - HELP_CLOSE_SIZE;
        return mouseX >= closeX && mouseX < HELP_X + panelWidth
                && mouseY >= HELP_Y && mouseY < HELP_Y + HELP_CLOSE_SIZE;
    }

    private boolean isOverHelpRestore(double mouseX, double mouseY) {
        return mouseX >= HELP_X && mouseX < HELP_X + 14
                && mouseY >= HELP_Y && mouseY < HELP_Y + 14;
    }

    private void renderWaypoints(GuiGraphics graphics) {
        if (minecraft == null || map == null) return;

        for (PJMWaypoint waypoint : PJMWaypointManager.INSTANCE.visible(minecraft)) {
            PJMMapRenderer.ScreenPoint point = map.worldToScreen(
                    waypoint.x(), waypoint.z(), 0, 0, width, height);
            int x = (int)Math.round(point.x());
            int y = (int)Math.round(point.y());
            if (x < -12 || y < -12 || x > width + 12 || y > height + 12) continue;

            PJMWaypointRenderer.renderIcon(graphics, font, waypoint, x, y, 12);
        }
    }

    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int rows = contextWaypoint == null ? 2 : 4;
        int x = contextMenuX;
        int y = contextMenuY;
        int menuHeight = CONTEXT_ROW_HEIGHT * rows;

        // Player marker is intentionally rendered at z=250. Render the menu at z=500 so a marker
        // can never punch through it, regardless of draw order.
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 500.0F);

        graphics.fill(x, y, x + CONTEXT_WIDTH, y + menuHeight, 0xF0182024);
        graphics.fill(x, y, x + CONTEXT_WIDTH, y + 1, 0xFF9AA5AA);
        graphics.fill(x, y + menuHeight - 1, x + CONTEXT_WIDTH, y + menuHeight, 0xFF11171A);

        int hoverRow = -1;
        if (mouseX >= x && mouseX < x + CONTEXT_WIDTH
                && mouseY >= y && mouseY < y + menuHeight) {
            hoverRow = (mouseY - y) / CONTEXT_ROW_HEIGHT;
        }

        for (int row = 0; row < rows; row++) {
            int rowTop = y + row * CONTEXT_ROW_HEIGHT;
            if (row == hoverRow) {
                graphics.fill(x + 1, rowTop, x + CONTEXT_WIDTH - 1,
                        rowTop + CONTEXT_ROW_HEIGHT, 0x88566F78);
            }

            Component label;
            int color = 0xFFFFFFFF;
            if (contextWaypoint == null) {
                label = Component.translatable(
                        row == 0
                                ? "screen.pjm.context.teleport"
                                : "screen.pjm.context.add_waypoint");
            } else {
                if (row == 0) {
                    label = Component.translatable("screen.pjm.context.teleport");
                } else if (row == 1) {
                    label = Component.translatable("screen.pjm.context.edit_waypoint");
                } else if (row == 2) {
                    label = Component.translatable(
                            contextWaypoint.enabled()
                                    ? "screen.pjm.waypoint.hide"
                                    : "screen.pjm.waypoint.show");
                } else {
                    label = Component.translatable("screen.pjm.context.delete_waypoint");
                    color = 0xFFFF7777;
                }
            }
            graphics.drawString(font, label, x + 7, rowTop + 6, color, false);
        }

        graphics.pose().popPose();
    }

    private boolean contextContains(double mouseX, double mouseY) {
        int rows = contextWaypoint == null ? 2 : 4;
        return contextMenuOpen
                && mouseX >= contextMenuX && mouseX < contextMenuX + CONTEXT_WIDTH
                && mouseY >= contextMenuY
                && mouseY < contextMenuY + CONTEXT_ROW_HEIGHT * rows;
    }

    private PJMWaypoint findWaypointAt(double mouseX, double mouseY) {
        if (minecraft == null || map == null) return null;

        PJMWaypoint best = null;
        double bestDistanceSq = WAYPOINT_HIT_RADIUS * WAYPOINT_HIT_RADIUS;

        for (PJMWaypoint waypoint : PJMWaypointManager.INSTANCE.visible(minecraft)) {
            PJMMapRenderer.ScreenPoint point = map.worldToScreen(
                    waypoint.x(), waypoint.z(), 0, 0, width, height);
            double dx = point.x() - mouseX;
            double dy = point.y() - mouseY;
            double distanceSq = dx * dx + dy * dy;
            if (distanceSq <= bestDistanceSq) {
                best = waypoint;
                bestDistanceSq = distanceSq;
            }
        }
        return best;
    }

    private void renderPlayer(GuiGraphics graphics) {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        PJMMapRenderer.ScreenPoint p = map.worldToScreen(
                minecraft.player.getX(), minecraft.player.getZ(), 0, 0, width, height);
        if (p.x() < -12 || p.y() < -12 || p.x() > width + 12 || p.y() > height + 12) {
            return;
        }
        PJMPlayerMarker.render(graphics, p.x(), p.y(), minecraft.player.getYRot(), 1.0f);
    }


    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            if (helpPanelVisible && isOverHelpClose(mouseX, mouseY)) {
                helpPanelVisible = false;
                contextMenuOpen = false;
                contextWaypoint = null;
                return true;
            }
            if (!helpPanelVisible && isOverHelpRestore(mouseX, mouseY)) {
                helpPanelVisible = true;
                contextMenuOpen = false;
                contextWaypoint = null;
                return true;
            }

            if (contextContains(mouseX, mouseY)) {
                int row = (int)((mouseY - contextMenuY) / CONTEXT_ROW_HEIGHT);
                PJMMapRenderer.WorldPoint selected = contextTarget;
                PJMWaypoint selectedWaypoint = contextWaypoint;
                contextMenuOpen = false;
                contextWaypoint = null;

                if (selectedWaypoint == null) {
                    if (row == 0 && selected != null) {
                        teleportToMapPoint(selected);
                        return true;
                    }
                    if (row == 1 && selected != null) {
                        beginAddWaypoint(selected);
                        return true;
                    }
                } else {
                    if (row == 0) {
                        teleportToWaypoint(selectedWaypoint);
                        return true;
                    }
                    if (row == 1) {
                        if (minecraft != null) {
                            minecraft.setScreen(new PJMWaypointEditScreen(
                                    this, selectedWaypoint));
                        }
                        return true;
                    }
                    if (row == 2) {
                        if (minecraft != null) {
                            PJMWaypointManager.INSTANCE.toggleEnabled(
                                    minecraft, selectedWaypoint.id());
                        }
                        return true;
                    }
                    if (row == 3) {
                        if (minecraft != null) {
                            minecraft.setScreen(new PJMDeleteWaypointConfirmScreen(
                                    this, selectedWaypoint));
                        }
                        return true;
                    }
                }
            }

            if (contextMenuOpen) {
                contextMenuOpen = false;
                contextWaypoint = null;
                return true;
            }
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && map != null) {
            PJMMapRenderer.WorldPoint target =
                    map.screenToWorld(mouseX, mouseY, 0, 0, width, height);

            if (hasShiftDown()) {
                contextMenuOpen = false;
                contextWaypoint = null;
                teleportToMapPoint(target);
                return true;
            }

            PJMWaypoint hitWaypoint = findWaypointAt(mouseX, mouseY);
            contextWaypoint = hitWaypoint;
            contextTarget = hitWaypoint == null
                    ? target
                    : new PJMMapRenderer.WorldPoint(hitWaypoint.x(), hitWaypoint.z());

            int rows = hitWaypoint == null ? 2 : 4;
            contextMenuX = Mth.clamp((int)Math.round(mouseX), 0,
                    Math.max(0, width - CONTEXT_WIDTH));
            contextMenuY = Mth.clamp((int)Math.round(mouseY), 0,
                    Math.max(0, height - CONTEXT_ROW_HEIGHT * rows));
            contextMenuOpen = true;
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void beginAddWaypoint(PJMMapRenderer.WorldPoint target) {
        if (minecraft == null || map == null || resolvingWaypointGround) return;

        double clampedX = Math.max(map.header().minWorldX(),
                Math.min(map.header().maxWorldX(), target.x()));
        double clampedZ = Math.max(map.header().minWorldZ(),
                Math.min(map.header().maxWorldZ(), target.z()));
        int blockX = Mth.floor(clampedX);
        int blockZ = Mth.floor(clampedZ);
        String defaultName = PJMWaypointManager.INSTANCE.nextDefaultName(minecraft);

        resolvingWaypointGround = true;
        PJMWorldActions.resolveGroundY(minecraft, blockX, blockZ, groundY -> {
            resolvingWaypointGround = false;
            if (minecraft != null) {
                minecraft.setScreen(new PJMWaypointEditScreen(
                        this, defaultName, blockX, groundY, blockZ));
            }
        });
    }

    /**
     * Shift+right-click and the context-menu Teleport action share the same verified ground logic.
     */
    private void teleportToMapPoint(PJMMapRenderer.WorldPoint target) {
        if (minecraft == null || minecraft.player == null || map == null) return;

        double clampedX = Math.max(map.header().minWorldX(),
                Math.min(map.header().maxWorldX(), target.x()));
        double clampedZ = Math.max(map.header().minWorldZ(),
                Math.min(map.header().maxWorldZ(), target.z()));

        if (PJMWorldActions.teleportToSurface(
                minecraft, clampedX, clampedZ,
                () -> minecraft.setScreen(null))) {
            contextMenuOpen = false;
        }
    }

    private void teleportToWaypoint(PJMWaypoint waypoint) {
        if (minecraft == null) return;
        if (PJMWorldActions.teleportToExact(
                minecraft,
                waypoint.x(), waypoint.y(), waypoint.z(),
                () -> minecraft.setScreen(null))) {
            contextMenuOpen = false;
            contextWaypoint = null;
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && map != null
                && !contextMenuOpen && !resolvingWaypointGround) {
            map.panPixels(dragX, dragY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (map != null) {
            map.zoomAt(mouseX, mouseY, delta, 0, 0, width, height);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_H) {
            helpPanelVisible = !helpPanelVisible;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_U && minecraft != null) {
            minecraft.setScreen(new PJMWaypointListScreen(this));
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME && map != null && minecraft != null && minecraft.player != null) {
            map.centerOn(minecraft.player.getX(), minecraft.player.getZ());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void removed() {
        if (map != null) {
            try {
                map.close();
            } catch (Exception ignored) {
            }
            map = null;
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
