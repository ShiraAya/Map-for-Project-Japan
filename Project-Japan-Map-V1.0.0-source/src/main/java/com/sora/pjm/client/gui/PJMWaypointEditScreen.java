package com.sora.pjm.client.gui;

import com.sora.pjm.waypoint.PJMWaypoint;
import com.sora.pjm.waypoint.PJMWaypointManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class PJMWaypointEditScreen extends Screen {
    private static final int PANEL_WIDTH = 304;
    private static final int PANEL_HEIGHT = 294;
    private static final int FIELD_WIDTH = 174;
    private static final int DEFAULT_COLOR = PJMWaypoint.DEFAULT_COLOR_RGB;

    private final Screen parent;
    private final PJMWaypoint editingWaypoint;
    private final String initialName;
    private final double initialX;
    private final double initialY;
    private final double initialZ;
    private final int initialColorRgb;
    private final String initialSymbol;
    private boolean enabled;

    private EditBox nameBox;
    private EditBox symbolBox;
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;
    private EditBox colorBox;
    private Button visibilityButton;
    private String validationError;

    /** Add-mode constructor. */
    public PJMWaypointEditScreen(Screen parent, String defaultName,
                                 double x, double y, double z) {
        this(parent, null, defaultName, x, y, z, DEFAULT_COLOR, "", true);
    }

    /** Edit-mode constructor. */
    public PJMWaypointEditScreen(Screen parent, PJMWaypoint waypoint) {
        this(parent, waypoint,
                waypoint.name(), waypoint.x(), waypoint.y(), waypoint.z(),
                waypoint.colorRgb(), waypoint.symbol(), waypoint.enabled());
    }

    private PJMWaypointEditScreen(Screen parent, PJMWaypoint editingWaypoint,
                                  String name,
                                  double x, double y, double z,
                                  int colorRgb,
                                  String symbol,
                                  boolean enabled) {
        super(Component.translatable(
                editingWaypoint == null
                        ? "screen.pjm.waypoint.title_add"
                        : "screen.pjm.waypoint.title_edit"));
        this.parent = parent;
        this.editingWaypoint = editingWaypoint;
        this.initialName = name;
        this.initialX = x;
        this.initialY = y;
        this.initialZ = z;
        this.initialColorRgb = colorRgb & 0xFFFFFF;
        this.initialSymbol = symbol == null ? "" : symbol;
        this.enabled = enabled;
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;

        int fieldX = left + 108;
        int firstY = top + 36;
        int spacing = 29;

        nameBox = field(fieldX, firstY, initialName, 80,
                "screen.pjm.waypoint.name");
        symbolBox = field(fieldX, firstY + spacing, initialSymbol, 2,
                "screen.pjm.waypoint.symbol");
        xBox = field(fieldX, firstY + spacing * 2, formatCoordinate(initialX), 24,
                "screen.pjm.waypoint.x");
        yBox = field(fieldX, firstY + spacing * 3, formatCoordinate(initialY), 24,
                "screen.pjm.waypoint.y");
        zBox = field(fieldX, firstY + spacing * 4, formatCoordinate(initialZ), 24,
                "screen.pjm.waypoint.z");
        colorBox = field(fieldX, firstY + spacing * 5,
                String.format("#%06X", initialColorRgb), 7,
                "screen.pjm.waypoint.color");

        visibilityButton = addRenderableWidget(Button.builder(
                        visibilityLabel(), button -> {
                            enabled = !enabled;
                            button.setMessage(visibilityLabel());
                        })
                .bounds(fieldX, firstY + spacing * 6, FIELD_WIDTH, 20)
                .build());

        int buttonY = top + PANEL_HEIGHT - 30;
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.waypoint.save"),
                        button -> save())
                .bounds(left + 58, buttonY, 84, 20)
                .build());

        addRenderableWidget(Button.builder(
                        Component.translatable("screen.pjm.waypoint.cancel"),
                        button -> onClose())
                .bounds(left + 162, buttonY, 84, 20)
                .build());

        setInitialFocus(nameBox);
    }

    private EditBox field(int x, int y, String value, int maxLength, String key) {
        EditBox box = new EditBox(font, x, y, FIELD_WIDTH, 20, Component.translatable(key));
        box.setValue(value);
        box.setMaxLength(maxLength);
        addRenderableWidget(box);
        return box;
    }

    private void save() {
        if (minecraft == null) return;

        String name = nameBox.getValue().trim();
        if (name.isEmpty()) {
            validationError = Component.translatable(
                    "screen.pjm.waypoint.error_name").getString();
            return;
        }

        try {
            double x = Double.parseDouble(xBox.getValue().trim());
            double y = Double.parseDouble(yBox.getValue().trim());
            double z = Double.parseDouble(zBox.getValue().trim());
            int color = parseColor(colorBox.getValue());
            String symbol = symbolBox.getValue().trim();

            if (editingWaypoint == null) {
                PJMWaypointManager.INSTANCE.add(
                        minecraft, PJMWaypoint.create(name, x, y, z, color, symbol, enabled));
            } else {
                PJMWaypointManager.INSTANCE.update(
                        minecraft, editingWaypoint.edited(
                                name, x, y, z, color, symbol, enabled));
            }
            minecraft.setScreen(parent);
        } catch (NumberFormatException invalidNumber) {
            validationError = Component.translatable(
                    "screen.pjm.waypoint.error_coordinate").getString();
        } catch (IllegalArgumentException invalidColor) {
            validationError = Component.translatable(
                    "screen.pjm.waypoint.error_color").getString();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        int firstY = top + 36;
        int spacing = 29;

        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE0182024);
        graphics.drawCenteredString(font, title, width / 2, top + 11, 0xFFFFFFFF);

        int labelX = left + 20;
        graphics.drawString(font, Component.translatable("screen.pjm.waypoint.name"),
                labelX, firstY + 6, 0xFFEAEAEA, false);
        graphics.drawString(font, Component.translatable("screen.pjm.waypoint.symbol"),
                labelX, firstY + spacing + 6, 0xFFEAEAEA, false);
        graphics.drawString(font, "X", labelX, firstY + spacing * 2 + 6,
                0xFFEAEAEA, false);
        graphics.drawString(font, "Y", labelX, firstY + spacing * 3 + 6,
                0xFFEAEAEA, false);
        graphics.drawString(font, "Z", labelX, firstY + spacing * 4 + 6,
                0xFFEAEAEA, false);
        graphics.drawString(font, Component.translatable("screen.pjm.waypoint.color"),
                labelX, firstY + spacing * 5 + 6, 0xFFEAEAEA, false);
        graphics.drawString(font, Component.translatable("screen.pjm.waypoint.visibility"),
                labelX, firstY + spacing * 6 + 6, 0xFFEAEAEA, false);

        int previewColor = DEFAULT_COLOR;
        try {
            previewColor = parseColor(colorBox == null ? "" : colorBox.getValue());
        } catch (IllegalArgumentException ignored) {
        }

        String previewName = nameBox == null ? initialName : nameBox.getValue().trim();
        String previewSymbol = symbolBox == null ? initialSymbol : symbolBox.getValue().trim();
        PJMWaypoint preview = PJMWaypoint.create(
                previewName.isBlank() ? "Waypoint" : previewName,
                0, 0, 0, previewColor, previewSymbol, enabled);
        PJMWaypointRenderer.renderIcon(graphics, font, preview,
                left + PANEL_WIDTH - 18, firstY + spacing * 5 + 10, 13,
                enabled ? 1.0F : 0.42F);

        if (validationError != null) {
            graphics.drawCenteredString(font, validationError,
                    width / 2, top + PANEL_HEIGHT - 48, 0xFFFF6B6B);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Component visibilityLabel() {
        return Component.translatable(
                enabled
                        ? "screen.pjm.waypoint.state_enabled"
                        : "screen.pjm.waypoint.state_disabled");
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

    private static int parseColor(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("Expected RRGGBB");
        }
        return Integer.parseInt(value, 16) & 0xFFFFFF;
    }

    private static String formatCoordinate(double value) {
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 0.000001D) {
            return Long.toString((long)rounded);
        }
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }
}
