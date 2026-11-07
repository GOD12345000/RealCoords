package dev.realcoords;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Bounded per-session cache for serialized Java chunk packets and later world-state overlays. */
final class ChunkReplayCache {
    enum OverlayType { BLOCK_UPDATE, BLOCK_ENTITY, LIGHT_UPDATE, CHUNK_BIOMES }

    record ChunkPos(int x, int z) { }
    record BlockPos(int x, int y, int z) { }

    record ReplayOverlay(OverlayType type, BlockPos position, byte[] payload) {
        ReplayOverlay {
            Objects.requireNonNull(type, "type");
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    record ReplayChunk(int x, int z, byte[] payload, List<ReplayOverlay> overlays) {
        ReplayChunk {
            payload = payload.clone();
            overlays = List.copyOf(overlays);
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private record OverlayKey(OverlayType type, BlockPos position) { }
    private record StoredChunk(byte[] bytes, int originalLength, boolean compressed) {
        long memorySize() { return bytes.length; }
    }
    private record StoredOverlay(byte[] bytes) {
        long memorySize() { return bytes.length + 24L; }
    }

    private final int maxChunks;
    private final long maxBytes;
    private final boolean compress;
    private final LinkedHashMap<ChunkPos, StoredChunk> chunks = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<ChunkPos, LinkedHashMap<OverlayKey, StoredOverlay>> overlays = new java.util.HashMap<>();
    private long storedBytes;

    ChunkReplayCache(int maxChunks, long maxBytes, boolean compress) {
        if (maxChunks < 1) throw new IllegalArgumentException("maxChunks must be positive");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxChunks = maxChunks;
        this.maxBytes = maxBytes;
        this.compress = compress;
    }

    /** Inserts/replaces a complete serialized chunk packet, retaining later overlays. */
    synchronized boolean put(int chunkX, int chunkZ, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > maxBytes) return false;
        StoredChunk replacement = encode(payload.clone());
        if (replacement.memorySize() > maxBytes) return false;

        ChunkPos key = new ChunkPos(chunkX, chunkZ);
        StoredChunk previous = chunks.remove(key);
        if (previous != null) {
            storedBytes -= previous.memorySize();
            // A newly received base chunk is newer than all previously captured overlays.
            subtractOverlays(key);
        }
        chunks.put(key, replacement);
        storedBytes += replacement.memorySize();
        evictToBudget();
        return chunks.containsKey(key);
    }

    /** Keeps only the latest update for a position/type while its base chunk is cached. */
    synchronized boolean putOverlay(int chunkX, int chunkZ, OverlayType type, BlockPos position, byte[] payload) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payload, "payload");
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        if (chunks.get(chunk) == null || payload.length + 24L > maxBytes) return false;

        LinkedHashMap<OverlayKey, StoredOverlay> perChunk =
                overlays.computeIfAbsent(chunk, ignored -> new LinkedHashMap<>());
        OverlayKey key = new OverlayKey(type, Objects.requireNonNull(position, "position"));
        StoredOverlay previous = perChunk.remove(key);
        if (previous != null) storedBytes -= previous.memorySize();
        StoredOverlay replacement = new StoredOverlay(payload.clone());
        perChunk.put(key, replacement);
        storedBytes += replacement.memorySize();
        evictToBudget();
        return chunks.containsKey(chunk);
    }

    synchronized ReplayChunk get(int chunkX, int chunkZ) {
        ChunkPos key = new ChunkPos(chunkX, chunkZ);
        StoredChunk stored = chunks.get(key);
        return stored == null ? null : replayChunk(key, stored);
    }

    synchronized boolean remove(int chunkX, int chunkZ) {
        ChunkPos key = new ChunkPos(chunkX, chunkZ);
        if (!chunks.containsKey(key)) return false;
        evictChunk(key);
        return true;
    }

    synchronized void evictOutside(int centerChunkX, int centerChunkZ, int radiusChunks) {
        if (radiusChunks < 0) {
            clear();
            return;
        }
        long radiusSquared = (long) radiusChunks * radiusChunks;
        var iterator = chunks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkPos, StoredChunk> entry = iterator.next();
            long dx = (long) entry.getKey().x() - centerChunkX;
            long dz = (long) entry.getKey().z() - centerChunkZ;
            if (dx * dx + dz * dz > radiusSquared) {
                storedBytes -= entry.getValue().memorySize();
                iterator.remove();
                subtractOverlays(entry.getKey());
            }
        }
    }

    synchronized List<ReplayChunk> snapshotNearestFirst(int centerChunkX, int centerChunkZ, int limit) {
        return snapshotNearestFirst(centerChunkX, centerChunkZ, 0, limit);
    }

    synchronized List<ReplayChunk> snapshotWithinRadius(int centerChunkX, int centerChunkZ,
                                                         int radiusChunks, int limit) {
        if (radiusChunks < 0) return List.of();
        long radiusSquared = (long) radiusChunks * radiusChunks;
        List<Map.Entry<ChunkPos, StoredChunk>> ordered = new ArrayList<>(chunks.entrySet());
        ordered.removeIf(entry -> distanceSquared(entry.getKey(), centerChunkX, centerChunkZ) > radiusSquared);
        ordered.sort(Comparator
                .comparingLong((Map.Entry<ChunkPos, StoredChunk> entry) ->
                        distanceSquared(entry.getKey(), centerChunkX, centerChunkZ))
                .thenComparingInt(entry -> entry.getKey().x())
                .thenComparingInt(entry -> entry.getKey().z()));
        List<ReplayChunk> result = new ArrayList<>(Math.min(limit, ordered.size()));
        for (Map.Entry<ChunkPos, StoredChunk> entry : ordered) {
            if (result.size() >= limit) break;
            result.add(replayChunk(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    synchronized List<ReplayChunk> snapshotNearestFirst(int centerChunkX, int centerChunkZ,
                                                         int startIndex, int limit) {
        if (limit <= 0 || startIndex < 0 || chunks.isEmpty()) return List.of();
        List<Map.Entry<ChunkPos, StoredChunk>> ordered = new ArrayList<>(chunks.entrySet());
        ordered.sort(Comparator
                .comparingLong((Map.Entry<ChunkPos, StoredChunk> entry) ->
                        distanceSquared(entry.getKey(), centerChunkX, centerChunkZ))
                .thenComparingInt(entry -> entry.getKey().x())
                .thenComparingInt(entry -> entry.getKey().z()));
        List<ReplayChunk> result = new ArrayList<>(Math.min(limit, Math.max(0, ordered.size() - startIndex)));
        int skipped = 0;
        for (Map.Entry<ChunkPos, StoredChunk> entry : ordered) {
            if (skipped++ < startIndex) continue;
            if (result.size() >= limit) break;
            result.add(replayChunk(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }

    synchronized int size() { return chunks.size(); }
    synchronized long storedBytes() { return storedBytes; }

    synchronized void clear() {
        chunks.clear();
        overlays.clear();
        storedBytes = 0;
    }

    private ReplayChunk replayChunk(ChunkPos pos, StoredChunk stored) {
        List<ReplayOverlay> result = new ArrayList<>();
        Map<OverlayKey, StoredOverlay> updates = overlays.get(pos);
        if (updates != null) {
            for (Map.Entry<OverlayKey, StoredOverlay> entry : updates.entrySet()) {
                OverlayKey key = entry.getKey();
                result.add(new ReplayOverlay(key.type(), key.position(), entry.getValue().bytes()));
            }
        }
        return new ReplayChunk(pos.x(), pos.z(), decode(stored), result);
    }

    private void evictToBudget() {
        var iterator = chunks.entrySet().iterator();
        while ((chunks.size() > maxChunks || storedBytes > maxBytes) && iterator.hasNext()) {
            Map.Entry<ChunkPos, StoredChunk> eldest = iterator.next();
            storedBytes -= eldest.getValue().memorySize();
            iterator.remove();
            subtractOverlays(eldest.getKey());
        }
    }

    private void evictChunk(ChunkPos key) {
        StoredChunk removed = chunks.remove(key);
        if (removed != null) storedBytes -= removed.memorySize();
        subtractOverlays(key);
    }

    private void subtractOverlays(ChunkPos key) {
        Map<OverlayKey, StoredOverlay> removed = overlays.remove(key);
        if (removed != null) {
            for (StoredOverlay overlay : removed.values()) storedBytes -= overlay.memorySize();
        }
    }

    private StoredChunk encode(byte[] raw) {
        if (!compress || raw.length == 0) return new StoredChunk(raw, raw.length, false);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DeflaterOutputStream deflater = new DeflaterOutputStream(
                    output, new Deflater(Deflater.BEST_SPEED))) {
                deflater.write(raw);
            }
            byte[] packed = output.toByteArray();
            return packed.length < raw.length
                    ? new StoredChunk(packed, raw.length, true)
                    : new StoredChunk(raw, raw.length, false);
        } catch (IOException impossible) {
            throw new IllegalStateException("In-memory chunk compression failed", impossible);
        }
    }

    private byte[] decode(StoredChunk stored) {
        if (!stored.compressed()) return stored.bytes().clone();
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(stored.bytes()));
             ByteArrayOutputStream output = new ByteArrayOutputStream(stored.originalLength())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > stored.originalLength()) {
                    throw new IllegalStateException("Corrupt chunk cache entry exceeds declared length");
                }
                output.write(buffer, 0, read);
            }
            byte[] decoded = output.toByteArray();
            if (decoded.length != stored.originalLength()) {
                throw new IllegalStateException("Corrupt chunk cache entry has an unexpected length");
            }
            return decoded;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to decompress cached chunk", exception);
        }
    }

    private static long distanceSquared(ChunkPos pos, int centerX, int centerZ) {
        long dx = (long) pos.x() - centerX;
        long dz = (long) pos.z() - centerZ;
        return dx * dx + dz * dz;
    }
}
