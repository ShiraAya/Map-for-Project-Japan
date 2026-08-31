package com.sora.pjm.client.gui;

import com.sora.pjm.waypoint.PJMWaypoint;
import com.sora.pjm.waypoint.PJMWaypointManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit second-step confirmation for deleting a waypoint. */
public final class PJMDeleteWaypointConfirmScreen extends Screen {
    private final Screen parent;
    private final PJMWaypoint waypoint;

    public PJMDeleteWaypointConfirmScreen(Screen parent, PJMWaypoint waypoint) {
        super(Component.translatable("screen.pjm.waypoint.delete_confirm_title"));
        this.parent = parent;
        this.waypoint = waypoint;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        int buttonY = height / 2 + 28;

        addRenderableWidget(Button.builder(
                Component.translatable("screen.pjm.waypoint.delete_confirm"),
                button -> confirmDelete())
                .bounds(centerX - 104, buttonY, 96, 20)
                .build());

        addRenderableWidget(Button.builder(
                Component.translatable("screen.pjm.waypoint.cancel"),
                button -> onClose())
                .bounds(centerX + 8, buttonY, 96, 20)
                .build());
    }

    private void confirmDelete() {
        if (minecraft != null) {
            PJMWaypointManager.INSTANCE.delete(minecraft, waypoint.id());
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int panelWidth = 300;
        int panelHeight = 112;
        int left = (width - panelWidth) / 2;
        int top = (height - panelHeight) / 2;

        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xE0182024);
        graphics.drawCenteredString(font, title, width / 2, top + 16, 0xFFFF7777);

        Component message = Component.translatable(
                "screen.pjm.waypoint.delete_confirm_message", waypoint.name());
        graphics.drawCenteredString(font, message, width / 2, top + 42, 0xFFFFFFFF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
