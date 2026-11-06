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

/**
 * Bounded per-session cache for serialized Java chunk packets.
 *
 * The cache deliberately knows nothing about MCProtocolLib packet classes. The
 * caller must serialize an immutable snapshot before insertion and deserialize
 * the returned payload on the channel event loop when replaying it.
 */
final class ChunkReplayCache {
    record ChunkPos(int x, int z) {
    }

    record ReplayChunk(int x, int z, byte[] payload) {
        ReplayChunk {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }
    }

    private record StoredChunk(byte[] bytes, int originalLength, boolean compressed) {
        long memorySize() {
            return bytes.length;
        }
    }

    private final int maxChunks;
    private final long maxBytes;
    private final boolean compress;
    private final LinkedHashMap<ChunkPos, StoredChunk> chunks = new LinkedHashMap<>(16, 0.75f, true);
    private long storedBytes;

    ChunkReplayCache(int maxChunks, long maxBytes, boolean compress) {
        if (maxChunks < 1) throw new IllegalArgumentException("maxChunks must be positive");
        if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
        this.maxChunks = maxChunks;
        this.maxBytes = maxBytes;
        this.compress = compress;
    }

    /**
     * Inserts or replaces a serialized chunk. Returns false if the payload alone
     * exceeds the configured memory budget; existing entries remain untouched.
     */
    synchronized boolean put(int chunkX, int chunkZ, byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > maxBytes) return false;

        byte[] raw = payload.clone();
        StoredChunk replacement = encode(raw);
        if (replacement.memorySize() > maxBytes) return false;

        ChunkPos key = new ChunkPos(chunkX, chunkZ);
        StoredChunk previous = chunks.remove(key);
        if (previous != null) storedBytes -= previous.memorySize();

        chunks.put(key, replacement);
        storedBytes += replacement.memorySize();
        evictToBudget();
        return chunks.containsKey(key);
    }

    synchronized boolean remove(int chunkX, int chunkZ) {
        StoredChunk removed = chunks.remove(new ChunkPos(chunkX, chunkZ));
        if (removed == null) return false;
        storedBytes -= removed.memorySize();
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
            }
        }
    }

    synchronized List<ReplayChunk> snapshotNearestFirst(int centerChunkX, int centerChunkZ, int limit) {
        if (limit <= 0 || chunks.isEmpty()) return List.of();
        List<Map.Entry<ChunkPos, StoredChunk>> ordered = new ArrayList<>(chunks.entrySet());
        ordered.sort(Comparator
                .comparingLong((Map.Entry<ChunkPos, StoredChunk> entry) -> distanceSquared(
                        entry.getKey(), centerChunkX, centerChunkZ))
                .thenComparingInt(entry -> entry.getKey().x())
                .thenComparingInt(entry -> entry.getKey().z()));

        List<ReplayChunk> result = new ArrayList<>(Math.min(limit, ordered.size()));
        for (Map.Entry<ChunkPos, StoredChunk> entry : ordered) {
            if (result.size() >= limit) break;
            ChunkPos pos = entry.getKey();
            result.add(new ReplayChunk(pos.x(), pos.z(), decode(entry.getValue())));
        }
        return List.copyOf(result);
    }

    synchronized int size() {
        return chunks.size();
    }

    synchronized long storedBytes() {
        return storedBytes;
    }

    synchronized void clear() {
        chunks.clear();
        storedBytes = 0;
    }

    private void evictToBudget() {
        var iterator = chunks.entrySet().iterator();
        while ((chunks.size() > maxChunks || storedBytes > maxBytes) && iterator.hasNext()) {
            StoredChunk eldest = iterator.next().getValue();
            storedBytes -= eldest.memorySize();
            iterator.remove();
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
