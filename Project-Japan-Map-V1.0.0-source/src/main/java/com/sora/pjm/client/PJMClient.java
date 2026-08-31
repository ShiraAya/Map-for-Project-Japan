package com.sora.pjm.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sora.pjm.PJM;
import com.sora.pjm.client.gui.PJMMapScreen;
import com.sora.pjm.client.gui.PJMWaypointEditScreen;
import com.sora.pjm.client.gui.PJMWaypointListScreen;
import com.sora.pjm.client.hud.PJMMinimapOverlay;
import com.sora.pjm.client.hud.PJMWorldWaypointOverlay;
import com.sora.pjm.map.PJMGeographyService;
import com.sora.pjm.waypoint.PJMWaypointManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class PJMClient {
    private static final KeyMapping OPEN_MAP = new KeyMapping(
            "key.pjm.open_map",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_M,
            "key.categories.pjm"
    );

    private static final KeyMapping TOGGLE_MINIMAP = new KeyMapping(
            "key.pjm.toggle_minimap",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "key.categories.pjm"
    );

    private static final KeyMapping ZOOM_MINIMAP = new KeyMapping(
            "key.pjm.zoom_minimap",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            "key.categories.pjm"
    );

    // Match the established Xaero workflow: B creates a waypoint, U opens the waypoint list.
    private static final KeyMapping CREATE_WAYPOINT = new KeyMapping(
            "key.pjm.create_waypoint",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_B,
            "key.categories.pjm"
    );

    private static final KeyMapping OPEN_WAYPOINTS = new KeyMapping(
            "key.pjm.open_waypoints",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U,
            "key.categories.pjm"
    );

    private PJMClient() {
    }

    @Mod.EventBusSubscriber(modid = PJM.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        private ModBusEvents() {
        }

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN_MAP);
            event.register(TOGGLE_MINIMAP);
            event.register(ZOOM_MINIMAP);
            event.register(CREATE_WAYPOINT);
            event.register(OPEN_WAYPOINTS);
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("world_waypoints", PJMWorldWaypointOverlay.INSTANCE);
            event.registerAboveAll("minimap", PJMMinimapOverlay.INSTANCE);
        }
    }

    @Mod.EventBusSubscriber(modid = PJM.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBusEvents {
        private ForgeBusEvents() {
        }

        @SubscribeEvent
        public static void clientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }

            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) {
                PJMMinimapOverlay.INSTANCE.reset();
                PJMWaypointManager.INSTANCE.reset();
                PJMGeographyService.INSTANCE.reset();
                return;
            }

            PJMWaypointManager.INSTANCE.ensureWorld(minecraft);
            PJMGeographyService.INSTANCE.tick(minecraft);

            if (minecraft.screen == null && OPEN_MAP.consumeClick()) {
                minecraft.setScreen(new PJMMapScreen());
            }

            if (TOGGLE_MINIMAP.consumeClick()) {
                PJMMinimapOverlay.INSTANCE.toggleVisible();
            }

            if (minecraft.screen == null && ZOOM_MINIMAP.consumeClick()) {
                PJMMinimapOverlay.INSTANCE.cycleZoom();
            }

            if (minecraft.screen == null && CREATE_WAYPOINT.consumeClick()) {
                String name = PJMWaypointManager.INSTANCE.nextDefaultName(minecraft);
                minecraft.setScreen(new PJMWaypointEditScreen(
                        null, name,
                        minecraft.player.getBlockX(),
                        minecraft.player.getBlockY(),
                        minecraft.player.getBlockZ()));
            }

            if (minecraft.screen == null && OPEN_WAYPOINTS.consumeClick()) {
                minecraft.setScreen(new PJMWaypointListScreen(null));
            }
        }
    }
}
