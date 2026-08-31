package com.sora.pjm.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asynchronous LRU GPU cache.
 *
 * <p>0.9 uses up to four worker threads because fast raster/vector tile generation is CPU-local and
 * short-lived. The render thread never performs PJ sampling, PNG encoding or disk writes.</p>
 */
public final class PJMTextureCache implements AutoCloseable {
    private final Minecraft minecraft;
    private final PJMMapSource source;
    private final int maxTextures;
    private final LinkedHashMap<PJMTileKey, CachedTexture> cache =
            new LinkedHashMap<>(64, 0.75f, true);
    private final Set<PJMTileKey> pending = ConcurrentHashMap.newKeySet();
    private final Set<PJMTileKey> knownEmpty = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers;
    private volatile boolean closed;

    public PJMTextureCache(Minecraft minecraft, PJMMapSource source, int maxTextures) {
        this.minecraft = minecraft;
        this.source = source;
        this.maxTextures = Math.max(16, maxTextures);

        int cpu = Runtime.getRuntime().availableProcessors();
        int workerCount = Math.max(2, Math.min(4, Math.max(1, cpu / 2)));
        AtomicInteger threadId = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable,
                    "PJM-Tile-Worker-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        workers = Executors.newFixedThreadPool(workerCount, factory);
    }

    /** Returns a texture and starts background loading if necessary. Render-thread only. */
    public ResourceLocation get(PJMTileKey key) {
        CachedTexture existing = cache.get(key);
        if (existing != null) return existing.location();
        if (knownEmpty.contains(key) || closed) return null;

        if (pending.add(key)) {
            workers.execute(() -> loadInBackground(key));
        }
        return null;
    }

    /** Returns only an already-uploaded texture and never queues work. Render-thread only. */
    public ResourceLocation getIfPresent(PJMTileKey key) {
        CachedTexture existing = cache.get(key);
        return existing == null ? null : existing.location();
    }

    /** True when the key is either an uploaded texture or a confirmed sparse/background tile. */
    public boolean isResolved(PJMTileKey key) {
        return cache.containsKey(key) || knownEmpty.contains(key);
    }

    public int pendingCount() {
        return pending.size();
    }

    private void loadInBackground(PJMTileKey key) {
        byte[] png = null;
        try {
            png = source.readTile(key);
        } catch (IOException | RuntimeException ignored) {
        }

        final byte[] tileBytes = png;
        if (tileBytes == null) knownEmpty.add(key);

        if (!closed && tileBytes != null) {
            minecraft.execute(() -> upload(key, tileBytes));
        }
        pending.remove(key);
    }

    private void upload(PJMTileKey key, byte[] png) {
        if (closed || cache.containsKey(key)) return;

        try (ByteArrayInputStream in = new ByteArrayInputStream(png)) {
            NativeImage image = NativeImage.read(in);
            DynamicTexture texture = new DynamicTexture(image);
            ResourceLocation location = minecraft.getTextureManager().register(
                    "pjm_tile_" + key.zoom() + "_" + key.x() + "_" + key.y(), texture);
            cache.put(key, new CachedTexture(location, texture));
            trim();
        } catch (IOException ignored) {
        }
    }

    private void trim() {
        while (cache.size() > maxTextures) {
            Iterator<Map.Entry<PJMTileKey, CachedTexture>> it = cache.entrySet().iterator();
            if (!it.hasNext()) return;

            CachedTexture old = it.next().getValue();
            it.remove();
            minecraft.getTextureManager().release(old.location());
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        workers.shutdownNow();

        for (CachedTexture texture : cache.values()) {
            minecraft.getTextureManager().release(texture.location());
        }
        cache.clear();
        pending.clear();
        knownEmpty.clear();
        source.close();
    }

    private record CachedTexture(ResourceLocation location, DynamicTexture texture) {
    }
}
