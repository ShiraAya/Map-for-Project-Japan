package com.sora.pjm.client.gui;

import com.sora.pjm.client.world.PJMWorldActions;
import com.sora.pjm.waypoint.PJMWaypoint;
import com.sora.pjm.waypoint.PJMWaypointManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Compact waypoint manager inspired by the established Xaero B/U workflow: U opens a list where
 * waypoints can be enabled/disabled, edited, teleported to and deleted.
 */
public final class PJMWaypointListScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int SIDE_MARGIN = 18;
    private static final int TOP = 42;
    private static final int BOTTOM = 44;

    private final Screen parent;
    private String selectedId;
    private int scrollRows;
    private SortMode sortMode = SortMode.DISTANCE;

    private Button editButton;
    private Button toggleButton;
    private Button teleportButton;
    private Button deleteButton;
    private Button sortButton;

    public PJMWaypointListScreen(Screen parent) {
        super(Component.translatable("screen.pjm.waypoint.list_title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonY = height - 30;
        int center = width / 2;

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.waypoint.add_current"),
                        button -> addCurrent())
                .bounds(center - 234, buttonY, 88, 20)
                .build());

        editButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.context.edit_waypoint"),
                        button -> editSelected())
                .bounds(center - 142, buttonY, 72, 20)
                .build());

        toggleButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.waypoint.hide"),
                        button -> toggleSelected())
                .bounds(center - 66, buttonY, 72, 20)
                .build());

        teleportButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.context.teleport"),
                        button -> teleportSelected())
                .bounds(center + 10, buttonY, 72, 20)
                .build());

        deleteButton = addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.context.delete_waypoint"),
                        button -> deleteSelected())
                .bounds(center + 86, buttonY, 72, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.waypoint.close"),
                        button -> onClose())
                .bounds(center + 162, buttonY, 72, 20)
                .build());

        sortButton = addRenderableWidget(Button.builder(sortLabel(), button -> cycleSort())
                .bounds(width - 118, 12, 100, 20)
                .build());

        validateSelection();
        updateButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(0, 0, width, height, 0xD012181D);
        graphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFFFF);

        List<PJMWaypoint> waypoints = sortedWaypoints();
        int listLeft = SIDE_MARGIN;
        int listRight = width - SIDE_MARGIN;
        int listTop = TOP;
        int listBottom = height - BOTTOM;
        graphics.fill(listLeft, listTop, listRight, listBottom, 0xA0101519);

        int visibleRows = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
        int maxScroll = Math.max(0, waypoints.size() - visibleRows);
        scrollRows = Mth.clamp(scrollRows, 0, maxScroll);

        if (waypoints.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("screen.pjm.waypoint.empty"),
                    width / 2, listTop + 18, 0xFFBFC7CC);
        } else {
            int end = Math.min(waypoints.size(), scrollRows + visibleRows);
            for (int i = scrollRows; i < end; i++) {
                PJMWaypoint waypoint = waypoints.get(i);
                int row = i - scrollRows;
                int y = listTop + row * ROW_HEIGHT;
                renderRow(graphics, waypoint, y, mouseX, mouseY, listLeft, listRight);
            }
        }

        if (maxScroll > 0) {
            int trackTop = listTop + 2;
            int trackBottom = listBottom - 2;
            int trackHeight = Math.max(1, trackBottom - trackTop);
            int thumbHeight = Math.max(14,
                    Math.round(trackHeight * (visibleRows / (float)waypoints.size())));
            int travel = Math.max(1, trackHeight - thumbHeight);
            int thumbY = trackTop + Math.round(travel * (scrollRows / (float)maxScroll));
            graphics.fill(listRight - 4, trackTop, listRight - 2, trackBottom, 0x553C454B);
            graphics.fill(listRight - 5, thumbY, listRight - 1, thumbY + thumbHeight,
                    0xCCB6C1C7);
        }

        updateButtons();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderRow(GuiGraphics graphics, PJMWaypoint waypoint,
                           int y, int mouseX, int mouseY,
                           int left, int right) {
        boolean selected = waypoint.id().equals(selectedId);
        boolean hovered = mouseX >= left && mouseX < right
                && mouseY >= y && mouseY < y + ROW_HEIGHT;

        if (selected) {
            graphics.fill(left + 1, y + 1, right - 1, y + ROW_HEIGHT - 1, 0x99607780);
        } else if (hovered) {
            graphics.fill(left + 1, y + 1, right - 1, y + ROW_HEIGHT - 1, 0x554B5C64);
        }

        float alpha = waypoint.enabled() ? 1.0F : 0.42F;
        PJMWaypointRenderer.renderIcon(graphics, font, waypoint,
                left + 14, y + ROW_HEIGHT / 2, 11, alpha);

        int primary = waypoint.enabled() ? 0xFFFFFFFF : 0xFF8A9297;
        int secondary = waypoint.enabled() ? 0xFFBEC8CD : 0xFF737B80;
        graphics.drawString(font, waypoint.name(), left + 28, y + 4, primary, false);

        String coord = String.format(Locale.ROOT, "X %.0f  Y %.0f  Z %.0f",
                waypoint.x(), waypoint.y(), waypoint.z());
        graphics.drawString(font, coord, left + 28, y + 14, secondary, false);

        if (minecraft != null && minecraft.player != null) {
            double dx = waypoint.x() - minecraft.player.getX();
            double dy = waypoint.y() - minecraft.player.getY();
            double dz = waypoint.z() - minecraft.player.getZ();
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String dist = formatDistance(distance);
            int distX = right - 12 - font.width(dist);
            graphics.drawString(font, dist, distX, y + 8, secondary, false);
        }

        String state = Component.translatable(
                waypoint.enabled()
                        ? "screen.pjm.waypoint.state_enabled"
                        : "screen.pjm.waypoint.state_disabled").getString();
        int stateX = right - 78 - font.width(state);
        if (stateX > left + 150) {
            graphics.drawString(font, state, stateX, y + 8,
                    waypoint.enabled() ? 0xFFB6E3A3 : 0xFFFFA0A0, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int listTop = TOP;
            int listBottom = height - BOTTOM;
            if (mouseX >= SIDE_MARGIN && mouseX < width - SIDE_MARGIN
                    && mouseY >= listTop && mouseY < listBottom) {
                List<PJMWaypoint> waypoints = sortedWaypoints();
                int row = (int)((mouseY - listTop) / ROW_HEIGHT);
                int index = scrollRows + row;
                if (index >= 0 && index < waypoints.size()) {
                    selectedId = waypoints.get(index).id();
                    updateButtons();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX >= SIDE_MARGIN && mouseX < width - SIDE_MARGIN
                && mouseY >= TOP && mouseY < height - BOTTOM) {
            List<PJMWaypoint> waypoints = sortedWaypoints();
            int visibleRows = Math.max(1, (height - BOTTOM - TOP) / ROW_HEIGHT);
            int maxScroll = Math.max(0, waypoints.size() - visibleRows);
            if (delta > 0) scrollRows--;
            if (delta < 0) scrollRows++;
            scrollRows = Mth.clamp(scrollRows, 0, maxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private List<PJMWaypoint> sortedWaypoints() {
        if (minecraft == null) return List.of();
        List<PJMWaypoint> result = new ArrayList<>(PJMWaypointManager.INSTANCE.all(minecraft));
        Comparator<PJMWaypoint> valueComparator;
        if (sortMode == SortMode.NAME) {
            valueComparator = Comparator.comparing(
                    PJMWaypoint::name, String.CASE_INSENSITIVE_ORDER);
        } else if (sortMode == SortMode.SYMBOL) {
            valueComparator = Comparator.comparing(
                            PJMWaypoint::symbol, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(PJMWaypoint::name, String.CASE_INSENSITIVE_ORDER);
        } else {
            valueComparator = Comparator.comparingDouble(this::distanceSqToPlayer)
                    .thenComparing(PJMWaypoint::name, String.CASE_INSENSITIVE_ORDER);
        }

        // XaeroPlus has a long-used option to keep enabled waypoints ahead of disabled ones.
        // It is especially useful here because hidden markers stay in the manager for restoration.
        Comparator<PJMWaypoint> comparator = Comparator
                .comparing(PJMWaypoint::enabled).reversed()
                .thenComparing(valueComparator);
        result.sort(comparator);
        return result;
    }

    private double distanceSqToPlayer(PJMWaypoint waypoint) {
        if (minecraft == null || minecraft.player == null) return 0.0D;
        double dx = waypoint.x() - minecraft.player.getX();
        double dy = waypoint.y() - minecraft.player.getY();
        double dz = waypoint.z() - minecraft.player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private void addCurrent() {
        if (minecraft == null || minecraft.player == null) return;
        String name = PJMWaypointManager.INSTANCE.nextDefaultName(minecraft);
        minecraft.setScreen(new PJMWaypointEditScreen(
                this, name,
                minecraft.player.getBlockX(), minecraft.player.getBlockY(), minecraft.player.getBlockZ()));
    }

    private void editSelected() {
        PJMWaypoint waypoint = selected();
        if (waypoint != null && minecraft != null) {
            minecraft.setScreen(new PJMWaypointEditScreen(this, waypoint));
        }
    }

    private void toggleSelected() {
        PJMWaypoint waypoint = selected();
        if (waypoint == null || minecraft == null) return;
        PJMWaypointManager.INSTANCE.toggleEnabled(minecraft, waypoint.id());
        updateButtons();
    }

    private void teleportSelected() {
        PJMWaypoint waypoint = selected();
        if (waypoint == null || minecraft == null) return;
        PJMWorldActions.teleportToExact(
                minecraft, waypoint.x(), waypoint.y(), waypoint.z(),
                () -> minecraft.setScreen(null));
    }

    private void deleteSelected() {
        PJMWaypoint waypoint = selected();
        if (waypoint != null && minecraft != null) {
            minecraft.setScreen(new PJMDeleteWaypointConfirmScreen(this, waypoint));
        }
    }

    private void cycleSort() {
        sortMode = switch (sortMode) {
            case DISTANCE -> SortMode.NAME;
            case NAME -> SortMode.SYMBOL;
            case SYMBOL -> SortMode.DISTANCE;
        };
        scrollRows = 0;
        if (sortButton != null) sortButton.setMessage(sortLabel());
    }

    private Component sortLabel() {
        String key = switch (sortMode) {
            case DISTANCE -> "screen.pjm.waypoint.sort_distance";
            case NAME -> "screen.pjm.waypoint.sort_name";
            case SYMBOL -> "screen.pjm.waypoint.sort_symbol";
        };
        return Component.translatable(key);
    }

    private PJMWaypoint selected() {
        if (minecraft == null || selectedId == null) return null;
        return PJMWaypointManager.INSTANCE.find(minecraft, selectedId);
    }

    private void validateSelection() {
        if (minecraft == null || selectedId == null) return;
        if (PJMWaypointManager.INSTANCE.find(minecraft, selectedId) == null) {
            selectedId = null;
        }
    }

    private void updateButtons() {
        validateSelection();
        PJMWaypoint selected = selected();
        boolean has = selected != null;
        if (editButton != null) editButton.active = has;
        if (toggleButton != null) {
            toggleButton.active = has;
            toggleButton.setMessage(Component.translatable(
                    has && selected.enabled()
                            ? "screen.pjm.waypoint.hide"
                            : "screen.pjm.waypoint.show"));
        }
        if (teleportButton != null) teleportButton.active = has;
        if (deleteButton != null) deleteButton.active = has;
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String formatDistance(double distance) {
        if (distance >= 1000.0D) {
            return String.format(Locale.ROOT, "%.1f km", distance / 1000.0D);
        }
        return Math.round(distance) + " m";
    }

    private enum SortMode {
        DISTANCE,
        NAME,
        SYMBOL
    }
}
